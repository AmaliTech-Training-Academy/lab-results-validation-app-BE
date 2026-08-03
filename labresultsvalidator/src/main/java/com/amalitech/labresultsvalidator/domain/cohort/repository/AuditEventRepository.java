package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * D5 AC1 — audit-log browsing, filtered by any combination of cohort/event-type/date-range.
     * The date-range bounds use {@code COALESCE(:param, e.occurredAt)} rather than
     * {@code :param IS NULL OR ...} — a bare {@code :from IS NULL} check gives Postgres no typed
     * context to bind an OffsetDateTime parameter against, which fails at prepare time with
     * "could not determine data type of parameter $N" whenever the filter is omitted. Wrapping
     * the parameter in COALESCE against the (typed) column it's compared to gives Postgres that
     * context, and is a no-op filter-wise when the bound value is null.
     */
    @Query("SELECT e FROM AuditEvent e WHERE "
        + "(:cohortId IS NULL OR e.cohortId = :cohortId) AND "
        + "(:eventType IS NULL OR e.eventType = :eventType) AND "
        + "e.occurredAt >= COALESCE(:from, e.occurredAt) AND "
        + "e.occurredAt <= COALESCE(:to, e.occurredAt)")
    Page<AuditEvent> search(
        @Param("cohortId") UUID cohortId,
        @Param("eventType") String eventType,
        @Param("from") OffsetDateTime from,
        @Param("to") OffsetDateTime to,
        Pageable pageable);
}
