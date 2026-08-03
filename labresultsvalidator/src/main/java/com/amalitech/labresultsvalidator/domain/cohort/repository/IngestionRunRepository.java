package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, UUID> {

    List<IngestionRun> findBySyncJobId(UUID syncJobId);

    /**
     * D5 AC1 — audit-log browsing, filtered by any combination of cohort/status/date-range/instructor.
     * The date-range bounds use {@code COALESCE(:param, r.runAt)} rather than
     * {@code :param IS NULL OR ...} — a bare {@code :from IS NULL} check gives Postgres no typed
     * context to bind an OffsetDateTime parameter against, which fails at prepare time with
     * "could not determine data type of parameter $N" whenever the filter is omitted. Wrapping
     * the parameter in COALESCE against the (typed) column it's compared to gives Postgres that
     * context, and is a no-op filter-wise when the bound value is null.
     */
    @Query("SELECT r FROM IngestionRun r WHERE "
        + "(:cohortId IS NULL OR r.cohortId = :cohortId) AND "
        + "(:status IS NULL OR r.status = :status) AND "
        + "r.runAt >= COALESCE(:from, r.runAt) AND "
        + "r.runAt <= COALESCE(:to, r.runAt) AND "
        + "(:instructorContactId IS NULL OR r.id IN "
        + "  (SELECT lr.ingestionRunId FROM LabResult lr WHERE lr.instructorContactId = :instructorContactId))")
    Page<IngestionRun> search(
        @Param("cohortId") UUID cohortId,
        @Param("status") String status,
        @Param("from") OffsetDateTime from,
        @Param("to") OffsetDateTime to,
        @Param("instructorContactId") UUID instructorContactId,
        Pageable pageable);
}
