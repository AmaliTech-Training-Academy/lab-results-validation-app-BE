package com.amalitech.labresultsvalidator.domain.cohort.entity;

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
 * Per-file row-processing audit record for the grading-ingestion pipeline (Epic B, B11). Distinct
 * from {@link CohortSyncFile}, which tracks file-byte-level change detection (B2-B4) — this table
 * has no {@code created_by}/{@code updated_by} columns, so it does not extend {@code BaseEntity}.
 */
@Entity
@Table(name = "ingestion_runs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    /** The {@code cohort_sync_jobs} run (B2-B4 file-byte layer) this row's file was processed under. */
    @Column(name = "sync_job_id", nullable = false)
    private UUID syncJobId;

    @Column(name = "workbook_filename", nullable = false, length = 255)
    private String workbookFilename;

    @Column(name = "sharepoint_file_url")
    private String sharepointFileUrl;

    @Column(name = "sharepoint_version_id", length = 200)
    private String sharepointVersionId;

    @Column(name = "quick_xor_hash", length = 128)
    private String quickXorHash;

    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    @Column(name = "triggered_by")
    private UUID triggeredBy;

    /** {@code SCHEDULED} or {@code MANUAL} (chk_trigger_type). */
    @Column(name = "trigger_type", nullable = false, length = 20)
    private String triggerType;

    /** {@code processing}/{@code completed}/{@code partial}/{@code failed}/{@code skipped} (chk_run_status). */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "processing";

    @Builder.Default
    @Column(name = "rows_read", nullable = false)
    private int rowsRead = 0;

    @Builder.Default
    @Column(name = "committed_new", nullable = false)
    private int committedNew = 0;

    @Builder.Default
    @Column(name = "updated_count", nullable = false)
    private int updatedCount = 0;

    @Builder.Default
    @Column(name = "skipped_invalid", nullable = false)
    private int skippedInvalid = 0;

    @Builder.Default
    @Column(name = "skipped_unchanged", nullable = false)
    private int skippedUnchanged = 0;

    @Builder.Default
    @Column(name = "conflicts_count", nullable = false)
    private int conflictsCount = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_report_json", columnDefinition = "jsonb")
    private String errorReportJson;

    /** B7 AC3 — true when {@code rejected / READY rows > 50%} for this file. */
    @Builder.Default
    @Column(name = "high_failure_rate", nullable = false)
    private boolean highFailureRate = false;

    @Builder.Default
    @Column(name = "failure_rate_percent", nullable = false)
    private double failureRatePercent = 0.0;

    @Builder.Default
    @Column(name = "run_at", nullable = false)
    private OffsetDateTime runAt = OffsetDateTime.now();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
