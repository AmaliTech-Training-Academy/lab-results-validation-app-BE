package com.amalitech.labresultsvalidator.domain.standup.repository;

import com.amalitech.labresultsvalidator.domain.standup.entity.CohortGate4Job;
import com.amalitech.labresultsvalidator.domain.standup.entity.CohortGate4JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CohortGate4JobRepository extends JpaRepository<CohortGate4Job, UUID> {

    boolean existsByCohortIdAndStatus(UUID cohortId, CohortGate4JobStatus status);

    Optional<CohortGate4Job> findTopByCohortIdOrderByStartedAtDesc(UUID cohortId);

    Page<CohortGate4Job> findByCohortIdOrderByStartedAtDesc(UUID cohortId, Pageable pageable);
}
