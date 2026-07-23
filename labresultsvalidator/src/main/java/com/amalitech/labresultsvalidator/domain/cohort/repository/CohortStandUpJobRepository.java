package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandUpJob;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandUpJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CohortStandUpJobRepository extends JpaRepository<CohortStandUpJob, UUID> {

    boolean existsByCohortIdAndStatus(UUID cohortId, CohortStandUpJobStatus status);
}
