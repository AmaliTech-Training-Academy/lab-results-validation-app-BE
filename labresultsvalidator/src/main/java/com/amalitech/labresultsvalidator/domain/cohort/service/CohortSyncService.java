package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortSyncJobResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.SyncBatchResponse;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortSyncJobRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
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
    private final CohortSyncJobRunner syncJobRunner;
    private final SyncEventService syncEventService;

    public CohortSyncJobResponse triggerSyncForCohort(UUID cohortId) {
        Cohort cohort = getEligibleCohort(cohortId);
        return CohortSyncJobResponse.from(startJob(cohort, currentUser().getId(), null));
    }

    public CohortSyncJobResponse triggerSyncForFile(UUID cohortId, String targetItemId) {
        Cohort cohort = getEligibleCohort(cohortId);
        return CohortSyncJobResponse.from(startJob(cohort, currentUser().getId(), targetItemId));
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
                startJob(cohort, actorId, null);
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

    private CohortSyncJob startJob(Cohort cohort, UUID actorId, String targetItemId) {
        UUID cohortId = cohort.getId();
        if (syncJobRepository.existsByCohortIdAndStatus(cohortId, CohortSyncJobStatus.RUNNING)) {
            throw new DuplicateResourceException(ALREADY_RUNNING_MESSAGE);
        }

        CohortSyncJob job = CohortSyncJob.builder()
            .cohort(cohort)
            .status(CohortSyncJobStatus.RUNNING)
            .startedAt(OffsetDateTime.now())
            .triggeredBy(actorId)
            .targetItemId(targetItemId)
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

        syncJobRunner.run(cohortId, job.getId(), actorId, targetItemId);
        return job;
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}