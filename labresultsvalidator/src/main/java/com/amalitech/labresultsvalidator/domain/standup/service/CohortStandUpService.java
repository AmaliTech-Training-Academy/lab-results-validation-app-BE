package com.amalitech.labresultsvalidator.domain.standup.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.standup.dto.StandUpJobResponse;
import com.amalitech.labresultsvalidator.domain.standup.dto.StandupRunResponse;
import com.amalitech.labresultsvalidator.domain.standup.dto.StreamJobHandle;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.standup.entity.CohortStandUpJob;
import com.amalitech.labresultsvalidator.domain.standup.entity.CohortStandUpJobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.standup.repository.CohortStandUpJobRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CohortStandUpService {

    private static final String ALREADY_RUNNING_MESSAGE =
        "A stand-up job is already running for this cohort";

    private final CohortRepository cohortRepository;
    private final CohortStandUpJobRepository standUpJobRepository;
    private final StandupJobRunner standupJobRunner;

    public StandUpJobResponse startStandUp(UUID cohortId) {
        Cohort cohort = cohortRepository.findById(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with ID: " + cohortId));

        if (cohort.getLifecycleState() != CohortLifecycleState.DRAFT) {
            throw new UnprocessableEntityException(
                "A stand-up job can only be started for a cohort in DRAFT");
        }
        if (cohort.getSharepointFolderUrl() == null || cohort.getSharepointFolderUrl().isBlank()) {
            throw new UnprocessableEntityException(
                "Attach a SharePoint folder link before starting a stand-up job");
        }
        if (standUpJobRepository.existsByCohortIdAndStatus(cohortId, CohortStandUpJobStatus.RUNNING)) {
            throw new DuplicateResourceException(ALREADY_RUNNING_MESSAGE);
        }

        UUID actorId = currentUser().getId();
        CohortStandUpJob job = CohortStandUpJob.builder()
            .cohort(cohort)
            .status(CohortStandUpJobStatus.RUNNING)
            .startedAt(OffsetDateTime.now())
            .triggeredBy(actorId)
            .build();

        try {
            job = standUpJobRepository.save(job);
        } catch (DataIntegrityViolationException ex) {
            // Fallback for the race between the existence check above and this insert,
            // guarded at the DB level by the partial unique index on (cohort_id) WHERE status='RUNNING'.
            throw new DuplicateResourceException(ALREADY_RUNNING_MESSAGE);
        }

        standupJobRunner.run(cohortId, job.getId(), actorId);

        return StandUpJobResponse.builder()
            .id(job.getId())
            .cohortId(cohortId)
            .status(job.getStatus())
            .startedAt(job.getStartedAt())
            .build();
    }

    public Page<StandupRunResponse> listRuns(UUID cohortId, Pageable pageable) {
        return standUpJobRepository.findByCohortIdOrderByStartedAtDesc(cohortId, pageable)
            .map(StandupRunResponse::from);
    }

    /** Resolves the most recent stand-up job for the SSE stream endpoint. */
    public StreamJobHandle getLatestJobForStream(UUID cohortId) {
        CohortStandUpJob job = standUpJobRepository.findTopByCohortIdOrderByStartedAtDesc(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("No stand-up job found for cohort " + cohortId));
        return new StreamJobHandle(job.getId(), job.getStatus() == CohortStandUpJobStatus.RUNNING);
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
