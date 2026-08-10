package com.amalitech.labresultsvalidator.domain.grading.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A held in-file duplicate row awaiting manual resolution (B8 AC4, B10). No
 * {@code created_by}/{@code updated_by} columns on this table, so it does not extend
 * {@code BaseEntity}.
 *
 * <p>No {@code cohortId} either — it was a denormalized copy of
 * {@code ingestionRunId -> IngestionRun.cohortId} (see V32); {@code IngestionConflictRepository}
 * scopes by cohort via a join/subquery on {@code ingestionRunId} instead.
 */
@Entity
@Table(name = "ingestion_conflicts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "ingestion_run_id", nullable = false)
    private UUID ingestionRunId;

    @Column(name = "learner_id")
    private UUID learnerId;

    @Column(name = "lab_id")
    private UUID labId;

    /** Only {@code in_file_duplicate} is allowed today (chk_conflict_kind). */
    @Builder.Default
    @Column(name = "conflict_kind", nullable = false, length = 30)
    private String conflictKind = "in_file_duplicate";

    /** The already-committed {@link LabResult} for this key, if one exists. */
    @Column(name = "existing_result_id")
    private UUID existingResultId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "incoming_payload_json", columnDefinition = "jsonb", nullable = false)
    private String incomingPayloadJson;

    /** {@code PENDING}/{@code RESOLVED}/{@code DISMISSED} (chk_conflict_status). */
    @Builder.Default
    @Column(nullable = false, length = 15)
    private String status = "PENDING";

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
