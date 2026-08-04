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

    /** D5 AC1 — audit-log browsing, filtered by any combination of cohort/event-type/date-range. */
    @Query("SELECT e FROM AuditEvent e WHERE "
        + "(:cohortId IS NULL OR e.cohortId = :cohortId) AND "
        + "(:eventType IS NULL OR e.eventType = :eventType) AND "
        + "(CAST(:from AS timestamp) IS NULL OR e.occurredAt >= :from) AND "
        + "(CAST(:to AS timestamp) IS NULL OR e.occurredAt <= :to)")
    Page<AuditEvent> search(
        @Param("cohortId") UUID cohortId,
        @Param("eventType") String eventType,
        @Param("from") OffsetDateTime from,
        @Param("to") OffsetDateTime to,
        Pageable pageable);
}
