package com.amalitech.labresultsvalidator.domain.grading.repository;

import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionConflict;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IngestionConflictRepository extends JpaRepository<IngestionConflict, UUID> {

    // cohortId isn't a column on this entity (removed in V32, see IngestionConflict's javadoc) —
    // every cohort-scoped lookup below resolves it via ingestionRunId -> IngestionRun.cohortId,
    // mirroring the existing findBySyncJobId/findBySyncJobIdAndStatus pattern.

    @Query("SELECT c FROM IngestionConflict c WHERE c.id = :id AND c.ingestionRunId IN "
        + "(SELECT r.id FROM IngestionRun r WHERE r.cohortId = :cohortId)")
    Optional<IngestionConflict> findByIdAndCohortId(@Param("id") UUID id, @Param("cohortId") UUID cohortId);

    /**
     * Same lookup as {@link #findByIdAndCohortId}, but takes a row-level write lock so two
     * concurrent {@code resolveConflict} calls for the same conflict serialize instead of both
     * reading {@code PENDING} and racing to commit (mirrors the same-file precedent in
     * {@code CohortSyncService#startJob} for the analogous double-trigger race).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM IngestionConflict c WHERE c.id = :id AND c.ingestionRunId IN "
        + "(SELECT r.id FROM IngestionRun r WHERE r.cohortId = :cohortId)")
    Optional<IngestionConflict> findByIdAndCohortIdForUpdate(@Param("id") UUID id, @Param("cohortId") UUID cohortId);

    /**
     * The most recent conflict raised for one learner+lab in a cohort, across every run (B10 AC3).
     *
     * <p>Ingestion uses this to avoid re-raising a duplicate it has already raised: a still-{@code
     * PENDING} conflict is refreshed in place rather than duplicated, and an already-decided one whose
     * candidate set is unchanged is left alone instead of reopening with a fresh alert email. Without
     * it, one duplicate pair in a workbook produced two new conflict rows and one new email per run,
     * forever, since the sheet is never written back to.
     */
    @Query("SELECT c FROM IngestionConflict c WHERE c.learnerId = :learnerId AND c.labId = :labId "
        + "AND c.ingestionRunId IN (SELECT r.id FROM IngestionRun r WHERE r.cohortId = :cohortId) "
        + "ORDER BY c.createdAt DESC, c.id DESC")
    List<IngestionConflict> findLatestForLearnerAndLab(
        @Param("cohortId") UUID cohortId, @Param("learnerId") UUID learnerId, @Param("labId") UUID labId,
        Pageable pageable);

    @Query("SELECT c FROM IngestionConflict c WHERE c.ingestionRunId IN "
        + "(SELECT r.id FROM IngestionRun r WHERE r.cohortId = :cohortId)")
    Page<IngestionConflict> findByCohortId(@Param("cohortId") UUID cohortId, Pageable pageable);

    @Query("SELECT c FROM IngestionConflict c WHERE c.status = :status AND c.ingestionRunId IN "
        + "(SELECT r.id FROM IngestionRun r WHERE r.cohortId = :cohortId)")
    Page<IngestionConflict> findByCohortIdAndStatus(
        @Param("cohortId") UUID cohortId, @Param("status") String status, Pageable pageable);

    @Query("SELECT c FROM IngestionConflict c WHERE c.ingestionRunId IN "
        + "(SELECT r.id FROM IngestionRun r WHERE r.syncJobId = :syncJobId)")
    Page<IngestionConflict> findBySyncJobId(@Param("syncJobId") UUID syncJobId, Pageable pageable);

    @Query("SELECT c FROM IngestionConflict c WHERE c.status = :status AND c.ingestionRunId IN "
        + "(SELECT r.id FROM IngestionRun r WHERE r.syncJobId = :syncJobId)")
    Page<IngestionConflict> findBySyncJobIdAndStatus(
        @Param("syncJobId") UUID syncJobId, @Param("status") String status, Pageable pageable);
}
