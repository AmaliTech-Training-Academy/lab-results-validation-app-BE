package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionConflict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IngestionConflictRepository extends JpaRepository<IngestionConflict, UUID> {

    List<IngestionConflict> findByCohortIdAndStatus(UUID cohortId, String status);
}
