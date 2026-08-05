package com.amalitech.labresultsvalidator.domain.grading.repository;

import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionConflict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface IngestionConflictRepository extends JpaRepository<IngestionConflict, UUID> {

    Optional<IngestionConflict> findByIdAndCohortId(UUID id, UUID cohortId);

    Page<IngestionConflict> findByCohortId(UUID cohortId, Pageable pageable);

    Page<IngestionConflict> findByCohortIdAndStatus(UUID cohortId, String status, Pageable pageable);

    @Query("SELECT c FROM IngestionConflict c WHERE c.ingestionRunId IN "
        + "(SELECT r.id FROM IngestionRun r WHERE r.syncJobId = :syncJobId)")
    Page<IngestionConflict> findBySyncJobId(@Param("syncJobId") UUID syncJobId, Pageable pageable);

    @Query("SELECT c FROM IngestionConflict c WHERE c.status = :status AND c.ingestionRunId IN "
        + "(SELECT r.id FROM IngestionRun r WHERE r.syncJobId = :syncJobId)")
    Page<IngestionConflict> findBySyncJobIdAndStatus(
        @Param("syncJobId") UUID syncJobId, @Param("status") String status, Pageable pageable);
}
