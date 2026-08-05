package com.amalitech.labresultsvalidator.domain.standup.repository;

import com.amalitech.labresultsvalidator.domain.standup.entity.CohortStandupPending;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CohortStandupPendingRepository extends JpaRepository<CohortStandupPending, UUID> {
}
