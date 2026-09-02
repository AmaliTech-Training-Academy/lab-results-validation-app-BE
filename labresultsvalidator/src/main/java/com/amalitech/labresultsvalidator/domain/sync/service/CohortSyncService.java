package com.amalitech.labresultsvalidator.domain.sync.service;

import com.amalitech.labresultsvalidator.common.exceptions.ConflictStateException;
import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.sync.dto.CohortSyncJobResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.ConflictCandidate;
import com.amalitech.labresultsvalidator.domain.grading.dto.ConflictResolutionAction;
import com.amalitech.labresultsvalidator.domain.sync.dto.GradingSyncOverviewResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.IngestionConflictResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.ResolveConflictRequest;
import com.amalitech.labresultsvalidator.domain.standup.dto.StreamJobHandle;
import com.amalitech.labresultsvalidator.domain.sync.dto.SyncBatchResponse;
import com.amalitech.labresultsvalidator.domain.sync.dto.SyncFileResponse;
import com.amalitech.labresultsvalidator.domain.sync.dto.SyncRunResponse;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncJobStatus;
import com.amalitech.labresultsvalidator.domain.sync.repository.CohortSyncFileRepository;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionConflict;
import com.amalitech.labresultsvalidator.domain.auditlog.entity.LabReferenceAuditLog;
import com.amalitech.labresultsvalidator.domain.auditlog.service.AuditEventService;
import com.amalitech.labresultsvalidator.domain.grading.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.grading.ingestion.ConflictPayloadCodec;
import com.amalitech.labresultsvalidator.domain.grading.ingestion.RowFingerprint;
import com.amalitech.labresultsvalidator.domain.grading.service.IngestionConflictViewAssembler;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.sync.repository.CohortSyncJobRepository;
import com.amalitech.labresultsvalidator.domain.grading.repository.IngestionConflictRepository;
import com.amalitech.labresultsvalidator.domain.grading.repository.IngestionRunRepository;
import com.amalitech.labresultsvalidator.domain.auditlog.repository.LabReferenceAuditLogRepository;
import com.amalitech.labresultsvalidator.domain.grading.repository.LabResultRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CohortSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(CohortSyncService.class);
    private static final String ALREADY_RUNNING_MESSAGE = "A sync job is already running for this cohort";

    private final CohortRepository cohortRepository;
    private final CohortSyncJobRepository syncJobRepository;
    private final CohortSyncFileRepository syncFileRepository;
    private final IngestionRunRepository ingestionRunRepository;
    private final IngestionConflictRepository ingestionConflictRepository;
    private final LabResultRepository labResultRepository;
    private final LabReferenceAuditLogRepository labReferenceAuditLogRepository;
    private final CohortSyncJobRunner syncJobRunner;
    private final SyncEventService syncEventService;
    private final AuditEventService auditEventService;
    private final IngestionConflictViewAssembler conflictViewAssembler;

    public CohortSyncJobResponse triggerSyncForCohort(UUID cohortId) {
        Cohort cohort = getEligibleCohort(cohortId);
        return CohortSyncJobResponse.from(startJob(cohort, currentUser().getId()));
    }

    /** Manual "whole run" trigger — attributes the batch to the authenticated caller. */
    public SyncBatchResponse triggerSyncForAll() {
        return runBatch(currentUser().getId());
    }

    /** Scheduled trigger — runs outside any request/security context, so there's no actor to attribute to. */
    public SyncBatchResponse triggerScheduledSyncForAll() {
        return runBatch(null);
    }

    /**
     * Scheduled trigger for a single cohort (dynamic per-cohort {@code SyncSchedule}). Skips
     * quietly rather than throwing — there's no caller to report an error to, so an ineligible or
     * already-running cohort just waits for the next fire.
     */
    public void triggerScheduledSyncForCohort(UUID cohortId) {
        Cohort cohort = cohortRepository.findById(cohortId).orElse(null);
        if (cohort == null
            || cohort.getLifecycleState() != CohortLifecycleState.STOOD_UP
            || !cohort.isActive()
            || cohort.getSharepointDriveId() == null
            || cohort.getSharepointItemId() == null) {
            LOG.info("[sync] scheduled cohort sync skipped: cohort {} not eligible", cohortId);
            return;
        }
        if (syncJobRepository.existsByCohortIdAndStatus(cohort.getId(), CohortSyncJobStatus.RUNNING)) {
            LOG.info("[sync] scheduled cohort sync skipped: cohort {} already has a running job", cohortId);
            return;
        }
        try {
            startJob(cohort, null);
        } catch (DuplicateResourceException ex) {
            LOG.info("[sync] scheduled cohort sync skipped: cohort {} started concurrently", cohortId);
        }
    }

    public Page<SyncRunResponse> listRuns(UUID cohortId, Pageable pageable) {
        return syncJobRepository.findByCohortIdOrderByStartedAtDesc(cohortId, pageable).map(SyncRunResponse::from);
    }

    public SyncRunResponse getRun(UUID cohortId, UUID jobId) {
        return SyncRunResponse.from(getJobOrThrow(cohortId, jobId));
    }

    public GradingSyncOverviewResponse getGradingSyncOverview(UUID cohortId, UUID jobId) {
        CohortSyncJob job = getJobOrThrow(cohortId, jobId);
        var runs = ingestionRunRepository.findBySyncJobId(jobId);
        return GradingSyncOverviewResponse.from(job, runs);
    }

    /**
     * Lists every file a sync run touched — new, changed, unchanged and failed alike — newest
     * first. Previously the only way to see which files failed (and why) after the fact was to
     * hold the run's SSE stream open; this exposes the same {@code cohort_sync_files} rows as a
     * plain, pollable list for a run-detail screen.
     */
    public Page<SyncFileResponse> listFilesForRun(UUID cohortId, UUID jobId, Pageable pageable) {
        getJobOrThrow(cohortId, jobId);
        return syncFileRepository.findBySyncJobIdOrderByCreatedAtDesc(jobId, pageable)
            .map(SyncFileResponse::from);
    }

    /**
     * Lists held in-file duplicate rows (B10) for a cohort, newest first, optionally narrowed to
     * one status (PENDING/RESOLVED/DISMISSED). Aggregates across every sync run for the cohort.
     */
    public Page<IngestionConflictResponse> listConflicts(UUID cohortId, String status, Pageable pageable) {
        Page<IngestionConflict> conflicts = (status == null || status.isBlank())
            ? ingestionConflictRepository.findByCohortId(cohortId, pageable)
            : ingestionConflictRepository.findByCohortIdAndStatus(cohortId, status.toUpperCase(), pageable);
        return conflictViewAssembler.assemble(conflicts, cohortId);
    }

    /**
     * Lists held in-file duplicate rows (B10) for a single sync run, newest first, optionally
     * narrowed to one status (PENDING/RESOLVED/DISMISSED).
     */
    public Page<IngestionConflictResponse> listConflictsForRun(
        UUID cohortId, UUID jobId, String status, Pageable pageable
    ) {
        getJobOrThrow(cohortId, jobId);
        Page<IngestionConflict> conflicts = (status == null || status.isBlank())
            ? ingestionConflictRepository.findBySyncJobId(jobId, pageable)
            : ingestionConflictRepository.findBySyncJobIdAndStatus(jobId, status.toUpperCase(), pageable);
        return conflictViewAssembler.assemble(conflicts, cohortId);
    }

    /**
     * Decides a held in-file duplicate (B10 AC2): one of the conflicting incoming rows becomes
     * authoritative, or the already-committed row stands, or all of them are rejected. Only a
     * {@code PENDING} conflict can be decided, and it is decided exactly once.
     *
     * <p>A duplicate is now one conflict holding every conflicting copy, so this takes a single
     * decision naming the winner via {@code chosenRowIndex}. Previously each copy was its own
     * conflict: {@code KEEP_EXISTING} wrote nothing, so the stored grade was whatever the last
     * {@code KEEP_INCOMING} carried and a later contradictory decision silently did nothing — the
     * outcome followed click order rather than the admin's intent.
     *
     * <p>Whatever the action, the decision is written to the grade's own audit history naming the
     * discarded marks. An operation that chose between two different scores must be answerable from
     * {@code lab_reference_audit_log} afterwards; recording only the winner (or, for
     * {@code KEEP_EXISTING}, nothing at all) is what made a dropped 98 invisible.
     */
    @Transactional
    public IngestionConflictResponse resolveConflict(UUID cohortId, UUID conflictId, ResolveConflictRequest request) {
        // Row-level lock: without it, two concurrent resolve calls for the same conflict can both
        // read PENDING before either commits, double-applying the resolution (see B10 race notes).
        IngestionConflict conflict = ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No conflict found with ID " + conflictId + " for cohort " + cohortId));

        if (!"PENDING".equals(conflict.getStatus())) {
            // 409, not 422: the request is well-formed, the conflict simply isn't open any more.
            throw new ConflictStateException(
                "Conflict " + conflictId + " has already been " + conflict.getStatus().toLowerCase()
                    + " and cannot be decided again.");
        }

        ConflictResolutionAction action = request.getAction();
        List<ConflictCandidate> candidates = ConflictPayloadCodec.read(conflict.getIncomingPayloadJson());
        UUID actorId = currentUser().getId();

        // Validate the decision before touching any data: a request that names no winner, or names one
        // that doesn't exist, is rejected without a lookup or a write.
        ConflictCandidate chosen = action == ConflictResolutionAction.KEEP_INCOMING
            ? selectCandidate(conflict, candidates, request.getChosenRowIndex())
            : null;

        LabResult existingResult = loadExistingResult(conflict, action);
        BigDecimal priorScore = existingResult != null ? existingResult.getScore() : null;
        List<ConflictCandidate> discarded = candidates.stream()
            .filter(c -> chosen == null || c.index() != chosen.index())
            .toList();

        UUID affectedResultId = chosen != null
            ? applyCandidate(conflict, chosen, existingResult, actorId).getId()
            : (existingResult != null ? existingResult.getId() : null);

        conflict.setStatus(action == ConflictResolutionAction.REJECT ? "DISMISSED" : "RESOLVED");
        conflict.setResolvedBy(actorId);
        conflict.setResolvedAt(OffsetDateTime.now());
        conflict.setResolutionNote(request.getNote());
        IngestionConflict saved = ingestionConflictRepository.save(conflict);

        recordDecisionInGradeHistory(conflict, chosen, discarded, priorScore, affectedResultId, actorId);
        recordDecisionEvent(cohortId, conflict, action, request, chosen, discarded, priorScore, actorId);

        return conflictViewAssembler.assemble(saved, cohortId);
    }

    /**
     * The already-committed row this duplicate was held against, if any. Only
     * {@code KEEP_INCOMING} needs it to exist — the other actions leave {@code lab_results} alone, so
     * a conflict whose committed row has since been deleted must still be closeable.
     */
    private LabResult loadExistingResult(IngestionConflict conflict, ConflictResolutionAction action) {
        if (conflict.getExistingResultId() == null) {
            return null;
        }
        LabResult existing = labResultRepository.findById(conflict.getExistingResultId()).orElse(null);
        if (existing == null && action == ConflictResolutionAction.KEEP_INCOMING) {
            throw new ResourceNotFoundException(
                "Existing lab result " + conflict.getExistingResultId() + " referenced by conflict "
                    + conflict.getId() + " no longer exists");
        }
        return existing;
    }

    /**
     * Resolves {@code chosenRowIndex} to the candidate it names. Required whenever a duplicate holds
     * more than one candidate: "keep the incoming row" cannot identify a winner among two rows with
     * different marks, and guessing one is exactly the silent data loss this fixes.
     */
    private ConflictCandidate selectCandidate(IngestionConflict conflict, List<ConflictCandidate> candidates,
                                              Integer chosenRowIndex) {
        if (candidates.isEmpty()) {
            // No readable candidate — e.g. the `{"error":"serialization failed"}` sentinel that
            // ConflictPayloadCodec falls back to. A clean 422 beats a raw NPE/500.
            throw new UnprocessableEntityException(
                "Conflict " + conflict.getId() + " has an incomplete or corrupted stored payload "
                    + "and cannot be committed as authoritative. Reject it and re-sync the file instead.");
        }
        if (chosenRowIndex == null) {
            if (candidates.size() > 1) {
                throw new UnprocessableEntityException(
                    "Conflict " + conflict.getId() + " holds " + candidates.size() + " conflicting rows ("
                        + summarize(candidates) + "), so chosenRowIndex is required to say which one is "
                        + "authoritative. Valid values are 0 to " + (candidates.size() - 1) + ".");
            }
            return requireCommittable(conflict, candidates.get(0));
        }
        if (chosenRowIndex < 0 || chosenRowIndex >= candidates.size()) {
            throw new UnprocessableEntityException(
                "chosenRowIndex " + chosenRowIndex + " is out of range for conflict " + conflict.getId()
                    + ", which holds " + candidates.size() + " conflicting row(s). Valid values are 0 to "
                    + (candidates.size() - 1) + ".");
        }
        return requireCommittable(conflict, candidates.get(chosenRowIndex));
    }

    private ConflictCandidate requireCommittable(IngestionConflict conflict, ConflictCandidate candidate) {
        if (!candidate.isCommittable()) {
            throw new UnprocessableEntityException(
                "The row at index " + candidate.index() + " of conflict " + conflict.getId()
                    + " has an incomplete or corrupted stored payload and cannot be committed as "
                    + "authoritative. Reject it and re-sync the file instead.");
        }
        return candidate;
    }

    /**
     * Applies the chosen candidate to {@code lab_results} — updating the row the conflict was held
     * against, if there was one, or creating a row otherwise.
     */
    private LabResult applyCandidate(IngestionConflict conflict, ConflictCandidate chosen,
                                     LabResult existingResult, UUID actorId) {
        String fingerprint = RowFingerprint.compute(chosen.submittedOn(), chosen.score());

        if (existingResult != null) {
            existingResult.setScore(chosen.score());
            existingResult.setSubmittedOn(chosen.submittedOn());
            existingResult.setInstructorContactId(chosen.instructorContactId());
            existingResult.setIngestionRunId(conflict.getIngestionRunId());
            existingResult.setRowValueHash(fingerprint);
            existingResult.setUpdatedBy(actorId);
            return labResultRepository.save(existingResult);
        }

        LabResult result = LabResult.builder()
            .learnerId(conflict.getLearnerId())
            .labId(conflict.getLabId())
            .ingestionRunId(conflict.getIngestionRunId())
            .instructorContactId(chosen.instructorContactId())
            .nspName(chosen.nspName())
            .score(chosen.score())
            .submittedOn(chosen.submittedOn())
            .rowValueHash(fingerprint)
            .build();
        result.setCreatedBy(actorId);
        result.setUpdatedBy(actorId);
        return labResultRepository.save(result);
    }

    /**
     * Writes the decision to the grade's own history (D3 AC1) — for every action, not just the one
     * that changes the score.
     *
     * <p>The reason names both what was kept and every mark that was dropped, so "why is this grade
     * what it is?" is answerable for the case that most needs it. A {@code KEEP_EXISTING} or
     * {@code REJECT} legitimately leaves the score alone, so its old and new values match; the record
     * exists because a decision between different marks was taken, which is not otherwise recoverable
     * from this table.
     *
     * <p>Skipped only when there is no row to attach to — a rejected duplicate that never had a
     * committed row. {@code lab_reference_audit_log.record_id} is {@code NOT NULL}, so that case is
     * carried by the {@code audit_event} payload alone.
     */
    private void recordDecisionInGradeHistory(IngestionConflict conflict, ConflictCandidate chosen,
                                              List<ConflictCandidate> discarded, BigDecimal priorScore,
                                              UUID affectedResultId, UUID actorId) {
        if (affectedResultId == null) {
            return;
        }
        BigDecimal newScore = chosen != null ? chosen.score() : priorScore;
        labReferenceAuditLogRepository.save(LabReferenceAuditLog.builder()
            .tableName("lab_results")
            .recordId(affectedResultId)
            .fieldName("score")
            .oldValue(priorScore != null ? priorScore.toPlainString() : null)
            .newValue(newScore != null ? newScore.toPlainString() : null)
            .changedBy(actorId)
            .reason(describeDecision(conflict, chosen, discarded, priorScore))
            .build());
    }

    /**
     * "Duplicate conflict &lt;id&gt; resolved: kept sheet Module-1 row 15 (98, reviewed 2026-08-09);
     * discarded sheet Module-1 row 5 (88)".
     */
    private String describeDecision(IngestionConflict conflict, ConflictCandidate chosen,
                                    List<ConflictCandidate> discarded, BigDecimal priorScore) {
        StringBuilder reason = new StringBuilder("Duplicate conflict ").append(conflict.getId());
        if (chosen != null) {
            reason.append(" resolved: kept ").append(chosen.location())
                .append(" (").append(plain(chosen.score()))
                .append(", reviewed ").append(chosen.submittedOn()).append(')');
        } else {
            reason.append(" resolved: kept the stored score ").append(plain(priorScore));
        }
        if (!discarded.isEmpty()) {
            reason.append("; discarded ").append(summarize(discarded));
        }
        return reason.toString();
    }

    /** "sheet Module-1 row 5 (88), sheet Module-1 row 15 (98)". */
    private String summarize(List<ConflictCandidate> candidates) {
        return candidates.stream()
            .map(c -> c.location() + " (" + plain(c.score()) + ")")
            .collect(Collectors.joining(", "));
    }

    private String plain(BigDecimal score) {
        return score != null ? score.toPlainString() : "none";
    }

    /**
     * Records the cohort-level audit event. Carries the full candidate set, not just the action, so
     * the audit view can show what the alternatives were — the only durable home for a discarded mark
     * when the duplicate had no committed row to attach a grade-history record to.
     */
    private void recordDecisionEvent(UUID cohortId, IngestionConflict conflict, ConflictResolutionAction action,
                                     ResolveConflictRequest request, ConflictCandidate chosen,
                                     List<ConflictCandidate> discarded, BigDecimal priorScore, UUID actorId) {
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("conflictId", conflict.getId().toString());
        auditPayload.put("action", action.name());
        auditPayload.put("learnerId", String.valueOf(conflict.getLearnerId()));
        auditPayload.put("labId", String.valueOf(conflict.getLabId()));
        auditPayload.put("priorScore", priorScore != null ? priorScore.toPlainString() : null);
        auditPayload.put("chosenRowIndex", chosen != null ? chosen.index() : null);
        auditPayload.put("keptRow", chosen != null ? describeCandidate(chosen) : null);
        auditPayload.put("discardedRows", discarded.stream().map(this::describeCandidate).toList());
        auditPayload.put("note", request.getNote() != null ? request.getNote() : "");
        auditEventService.record(
            action == ConflictResolutionAction.REJECT ? "CONFLICT_DISMISSED" : "CONFLICT_RESOLVED",
            cohortId, actorId, auditPayload);
    }

    private Map<String, Object> describeCandidate(ConflictCandidate candidate) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("index", candidate.index());
        described.put("fileName", candidate.fileName());
        described.put("sheetName", candidate.sheetName());
        described.put("rowNum", candidate.rowNum());
        described.put("score", candidate.score() != null ? candidate.score().toPlainString() : null);
        described.put("submittedOn", candidate.submittedOn() != null ? candidate.submittedOn().toString() : null);
        return described;
    }

    /** Resolves the most recent sync job for the SSE stream endpoint. */
    public StreamJobHandle getLatestJobForStream(UUID cohortId) {
        CohortSyncJob job = syncJobRepository.findTopByCohortIdOrderByStartedAtDesc(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("No sync job found for cohort " + cohortId));
        return new StreamJobHandle(job.getId(), job.getStatus() == CohortSyncJobStatus.RUNNING);
    }

    private CohortSyncJob getJobOrThrow(UUID cohortId, UUID jobId) {
        return syncJobRepository.findByIdAndCohortId(jobId, cohortId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No sync job found with ID " + jobId + " for cohort " + cohortId));
    }

    /**
     * Runs a sync for every eligible (STOOD_UP, active) cohort. A cohort already mid-run is
     * skipped, not treated as a batch failure — one busy cohort shouldn't block the rest (B1 AC1/AC3).
     */
    private SyncBatchResponse runBatch(UUID actorId) {
        List<Cohort> eligible =
            cohortRepository.findAllByLifecycleStateAndIsActiveTrue(CohortLifecycleState.STOOD_UP);

        List<UUID> triggered = new ArrayList<>();
        int skipped = 0;

        for (Cohort cohort : eligible) {
            if (syncJobRepository.existsByCohortIdAndStatus(cohort.getId(), CohortSyncJobStatus.RUNNING)) {
                LOG.info("[sync] cohort={} already has a running sync job — skipping in this batch", cohort.getId());
                skipped++;
                continue;
            }
            try {
                startJob(cohort, actorId);
                triggered.add(cohort.getId());
            } catch (DuplicateResourceException ex) {
                LOG.info("[sync] cohort={} started running between check and insert — skipping in this batch",
                    cohort.getId());
                skipped++;
            }
        }

        LOG.info("[sync] batch run: {} triggered, {} skipped (of {} eligible cohorts)",
            triggered.size(), skipped, eligible.size());
        return new SyncBatchResponse(triggered.size(), skipped, triggered);
    }

    private Cohort getEligibleCohort(UUID cohortId) {
        Cohort cohort = cohortRepository.findById(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with ID: " + cohortId));
        if (cohort.getLifecycleState() != CohortLifecycleState.STOOD_UP) {
            throw new UnprocessableEntityException("Cohort must be in STOOD_UP state to sync score sheets.");
        }
        if (cohort.getSharepointDriveId() == null || cohort.getSharepointItemId() == null) {
            throw new UnprocessableEntityException("Cohort is missing SharePoint drive reference.");
        }
        return cohort;
    }

    private CohortSyncJob startJob(Cohort cohort, UUID actorId) {
        UUID cohortId = cohort.getId();
        if (syncJobRepository.existsByCohortIdAndStatus(cohortId, CohortSyncJobStatus.RUNNING)) {
            throw new DuplicateResourceException(ALREADY_RUNNING_MESSAGE);
        }

        CohortSyncJob job = CohortSyncJob.builder()
            .cohort(cohort)
            .status(CohortSyncJobStatus.RUNNING)
            .startedAt(OffsetDateTime.now())
            .triggeredBy(actorId)
            .build();

        try {
            job = syncJobRepository.save(job);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateResourceException(ALREADY_RUNNING_MESSAGE);
        }

        Map<String, Object> startedPayload = new LinkedHashMap<>();
        startedPayload.put("cohortId", cohortId.toString());
        startedPayload.put("status", CohortSyncJobStatus.RUNNING.name());
        startedPayload.put("startedAt", job.getStartedAt().toString());
        syncEventService.emit(job.getId(), "sync.started", startedPayload);

        syncJobRunner.run(cohortId, job.getId(), actorId);
        return job;
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}