package com.amalitech.labresultsvalidator.domain.sync.repository;

import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncJobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface CohortSyncJobRepository extends JpaRepository<CohortSyncJob, UUID> {

    boolean existsByCohortIdAndStatus(UUID cohortId, CohortSyncJobStatus status);

    Optional<CohortSyncJob> findTopByCohortIdOrderByStartedAtDesc(UUID cohortId);

    Page<CohortSyncJob> findByCohortIdOrderByStartedAtDesc(UUID cohortId, Pageable pageable);

    Optional<CohortSyncJob> findByIdAndCohortId(UUID id, UUID cohortId);

    /** The run immediately before a given one, for the same cohort — used to say "no changes
     *  since &lt;this run's completedAt&gt;" on a SKIPPED job's overview. */
    Optional<CohortSyncJob> findFirstByCohortIdAndStartedAtBeforeOrderByStartedAtDesc(
        UUID cohortId, OffsetDateTime startedAt);
}