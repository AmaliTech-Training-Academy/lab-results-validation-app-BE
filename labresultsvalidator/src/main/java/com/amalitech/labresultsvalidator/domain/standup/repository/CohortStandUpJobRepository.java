package com.amalitech.labresultsvalidator.domain.standup.repository;

import com.amalitech.labresultsvalidator.domain.standup.entity.CohortStandUpJob;
import com.amalitech.labresultsvalidator.domain.standup.entity.CohortStandUpJobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CohortStandUpJobRepository extends JpaRepository<CohortStandUpJob, UUID> {

    boolean existsByCohortIdAndStatus(UUID cohortId, CohortStandUpJobStatus status);

    Optional<CohortStandUpJob> findTopByCohortIdOrderByStartedAtDesc(UUID cohortId);

    Page<CohortStandUpJob> findByCohortIdOrderByStartedAtDesc(UUID cohortId, Pageable pageable);
}
