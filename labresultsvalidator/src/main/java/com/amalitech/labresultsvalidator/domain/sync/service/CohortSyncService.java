package com.amalitech.labresultsvalidator.domain.sync.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.sync.dto.CohortSyncJobResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.ConflictResolutionAction;
import com.amalitech.labresultsvalidator.domain.sync.dto.GradingSyncOverviewResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.IngestionConflictResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.ResolveConflictRequest;
import com.amalitech.labresultsvalidator.domain.standup.dto.StreamJobHandle;
import com.amalitech.labresultsvalidator.domain.sync.dto.SyncBatchResponse;
import com.amalitech.labresultsvalidator.domain.sync.dto.SyncRunResponse;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncJobStatus;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionConflict;
import com.amalitech.labresultsvalidator.domain.auditlog.entity.LabReferenceAuditLog;
import com.amalitech.labresultsvalidator.domain.auditlog.service.AuditEventService;
import com.amalitech.labresultsvalidator.domain.grading.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.grading.ingestion.RowFingerprint;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CohortSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(CohortSyncService.class);
    private static final String ALREADY_RUNNING_MESSAGE = "A sync job is already running for this cohort";

    private final CohortRepository cohortRepository;
    private final CohortSyncJobRepository syncJobRepository;
    private final IngestionRunRepository ingestionRunRepository;
    private final IngestionConflictRepository ingestionConflictRepository;
    private final LabResultRepository labResultRepository;
    private final LabReferenceAuditLogRepository labReferenceAuditLogRepository;
    private final CohortSyncJobRunner syncJobRunner;
    private final SyncEventService syncEventService;
    private final AuditEventService auditEventService;

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
     * Lists held in-file duplicate rows (B10) for a cohort, newest first, optionally narrowed to
     * one status (PENDING/RESOLVED/DISMISSED). Aggregates across every sync run for the cohort.
     */
    public Page<IngestionConflictResponse> listConflicts(UUID cohortId, String status, Pageable pageable) {
        Page<IngestionConflict> conflicts = (status == null || status.isBlank())
            ? ingestionConflictRepository.findByCohortId(cohortId, pageable)
            : ingestionConflictRepository.findByCohortIdAndStatus(cohortId, status.toUpperCase(), pageable);
        return conflicts.map(IngestionConflictResponse::from);
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
        return conflicts.map(IngestionConflictResponse::from);
    }

    /**
     * Resolves a held in-file duplicate conflict (B10): picks the existing committed row, the
     * incoming row, or neither as authoritative. Only a {@code PENDING} conflict can be resolved.
     */
    @Transactional
    public IngestionConflictResponse resolveConflict(UUID cohortId, UUID conflictId, ResolveConflictRequest request) {
        // Row-level lock: without it, two concurrent resolve calls for the same conflict can both
        // read PENDING before either commits, double-applying the resolution (see B10 race notes).
        IngestionConflict conflict = ingestionConflictRepository.findByIdAndCohortIdForUpdate(conflictId, cohortId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No conflict found with ID " + conflictId + " for cohort " + cohortId));

        if (!"PENDING".equals(conflict.getStatus())) {
            throw new UnprocessableEntityException(
                "Conflict " + conflictId + " has already been " + conflict.getStatus().toLowerCase()
                    + " and cannot be resolved again.");
        }

        UUID actorId = currentUser().getId();
        if (request.getAction() == ConflictResolutionAction.KEEP_INCOMING) {
            commitIncomingAsAuthoritative(conflict, actorId);
        }
        conflict.setStatus(request.getAction() == ConflictResolutionAction.REJECT ? "DISMISSED" : "RESOLVED");
        conflict.setResolvedBy(actorId);
        conflict.setResolvedAt(OffsetDateTime.now());
        conflict.setResolutionNote(request.getNote());

        IngestionConflictResponse response = IngestionConflictResponse.from(ingestionConflictRepository.save(conflict));

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("conflictId", conflictId.toString());
        auditPayload.put("action", request.getAction().name());
        auditPayload.put("note", request.getNote() != null ? request.getNote() : "");
        auditEventService.record(
            request.getAction() == ConflictResolutionAction.REJECT ? "CONFLICT_DISMISSED" : "CONFLICT_RESOLVED",
            cohortId, actorId, auditPayload);

        return response;
    }

    /**
     * Applies the conflict's held incoming payload to {@code lab_results} — updating the existing
     * row it was held against, if there was one, or creating a new row otherwise.
     */
    private void commitIncomingAsAuthoritative(IngestionConflict conflict, UUID actorId) {
        Map<String, Object> payload = IngestionConflictResponse.parsePayload(conflict.getIncomingPayloadJson());

        LocalDate submittedOn;
        BigDecimal score;
        String nspName;
        UUID instructorContactId;
        try {
            submittedOn = LocalDate.parse((String) payload.get("submittedOn"));
            score = new BigDecimal((String) payload.get("score"));
            nspName = (String) payload.get("nspName");
            Object instructorContactIdRaw = payload.get("instructorContactId");
            instructorContactId = instructorContactIdRaw != null
                ? UUID.fromString((String) instructorContactIdRaw)
                : null;
        } catch (NullPointerException | IllegalArgumentException | DateTimeParseException ex) {
            // The stored payload is missing fields or unparseable — e.g. LabResultCommitService's
            // own serialization fallback stored the `{"error":"serialization failed"}` sentinel
            // instead of the real row. Surface a clean 422 instead of a raw NPE/500.
            throw new UnprocessableEntityException(
                "Conflict " + conflict.getId() + " has an incomplete or corrupted stored payload "
                    + "and cannot be committed as authoritative. Reject it and re-sync the file instead.");
        }
        String fingerprint = RowFingerprint.compute(submittedOn, score);

        if (conflict.getExistingResultId() != null) {
            LabResult existing = labResultRepository.findById(conflict.getExistingResultId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Existing lab result " + conflict.getExistingResultId() + " referenced by conflict "
                        + conflict.getId() + " no longer exists"));
            BigDecimal oldScore = existing.getScore();

            existing.setScore(score);
            existing.setSubmittedOn(submittedOn);
            existing.setInstructorContactId(instructorContactId);
            existing.setIngestionRunId(conflict.getIngestionRunId());
            existing.setRowValueHash(fingerprint);
            existing.setUpdatedBy(actorId);
            labResultRepository.save(existing);

            labReferenceAuditLogRepository.save(LabReferenceAuditLog.builder()
                .tableName("lab_results")
                .recordId(existing.getId())
                .fieldName("score")
                .oldValue(oldScore.toPlainString())
                .newValue(score.toPlainString())
                .changedBy(actorId)
                .reason("Ingestion conflict " + conflict.getId() + " resolved: kept incoming row")
                .build());
        } else {
            LabResult result = LabResult.builder()
                .learnerId(conflict.getLearnerId())
                .labId(conflict.getLabId())
                .ingestionRunId(conflict.getIngestionRunId())
                .instructorContactId(instructorContactId)
                .nspName(nspName)
                .score(score)
                .submittedOn(submittedOn)
                .rowValueHash(fingerprint)
                .build();
            result.setCreatedBy(actorId);
            result.setUpdatedBy(actorId);
            labResultRepository.save(result);
        }
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