package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.Gate4JobResponse;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortGate4Job;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortGate4JobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortGate4JobRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CohortGate4Service {

    private static final String ALREADY_RUNNING_MESSAGE = "A Gate 4 job is already running for this cohort";

    private final CohortRepository cohortRepository;
    private final CohortGate4JobRepository gate4JobRepository;
    private final Gate4JobRunner gate4JobRunner;

    public Gate4JobResponse startGate4(UUID cohortId) {
        Cohort cohort = cohortRepository.findById(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with ID: " + cohortId));

        if (cohort.getLifecycleState() != CohortLifecycleState.REFERENCE_ACCEPTED) {
            throw new UnprocessableEntityException(
                "Cohort must be in REFERENCE_ACCEPTED state to run Gate 4.");
        }
        if (cohort.getSharepointDriveId() == null || cohort.getSharepointItemId() == null) {
            throw new UnprocessableEntityException(
                "Cohort is missing SharePoint drive reference. Re-run stand-up.");
        }
        if (gate4JobRepository.existsByCohortIdAndStatus(cohortId, CohortGate4JobStatus.RUNNING)) {
            throw new DuplicateResourceException(ALREADY_RUNNING_MESSAGE);
        }

        UUID actorId = currentUser().getId();
        CohortGate4Job job = CohortGate4Job.builder()
            .cohort(cohort)
            .status(CohortGate4JobStatus.RUNNING)
            .startedAt(OffsetDateTime.now())
            .triggeredBy(actorId)
            .build();

        try {
            job = gate4JobRepository.save(job);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateResourceException(ALREADY_RUNNING_MESSAGE);
        }

        gate4JobRunner.run(cohortId, job.getId(), actorId);
        return Gate4JobResponse.from(job);
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
