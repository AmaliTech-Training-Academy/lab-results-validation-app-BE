package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandupPending;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CohortStandupPendingRepository extends JpaRepository<CohortStandupPending, UUID> {
}
