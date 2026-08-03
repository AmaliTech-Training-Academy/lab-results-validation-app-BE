package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionConflict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IngestionConflictRepository extends JpaRepository<IngestionConflict, UUID> {

    Page<IngestionConflict> findByCohortId(UUID cohortId, Pageable pageable);

    Page<IngestionConflict> findByCohortIdAndStatus(UUID cohortId, String status, Pageable pageable);
}
