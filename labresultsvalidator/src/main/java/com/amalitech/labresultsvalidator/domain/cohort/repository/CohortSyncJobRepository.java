package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CohortSyncJobRepository extends JpaRepository<CohortSyncJob, UUID> {

    boolean existsByCohortIdAndStatus(UUID cohortId, CohortSyncJobStatus status);

    Optional<CohortSyncJob> findTopByCohortIdOrderByStartedAtDesc(UUID cohortId);

    Page<CohortSyncJob> findByCohortIdOrderByStartedAtDesc(UUID cohortId, Pageable pageable);

    Optional<CohortSyncJob> findByIdAndCohortId(UUID id, UUID cohortId);
}