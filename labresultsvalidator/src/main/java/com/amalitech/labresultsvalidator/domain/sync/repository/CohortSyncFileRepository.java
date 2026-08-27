package com.amalitech.labresultsvalidator.domain.sync.repository;

import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CohortSyncFileRepository extends JpaRepository<CohortSyncFile, UUID> {

    /**
     * cohort_id isn't a column on this entity (removed as a redundant transitive dependency on
     * sync_job_id -> CohortSyncJob.cohort_id — every row's sync_job_id already fixes its cohort,
     * mirroring how IngestionConflict resolves cohort scoping via its own run/job association).
     */
    @Query("SELECT f FROM CohortSyncFile f WHERE f.syncJob.cohort.id = :cohortId AND f.fileName = :fileName "
        + "ORDER BY f.createdAt DESC")
    List<CohortSyncFile> findByCohortIdAndFileNameOrderByCreatedAtDesc(
        @Param("cohortId") UUID cohortId, @Param("fileName") String fileName);
}
