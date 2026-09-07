package com.amalitech.labresultsvalidator.domain.grading.dto;

import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionRun;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointCTag;

import java.time.OffsetDateTime;
import java.util.UUID;

/** D5 list-view row — summary only, no {@code errorReportJson} (see {@link IngestionRunDetailResponse}). */
public record IngestionRunAuditResponse(
    UUID id,
    UUID cohortId,
    UUID syncJobId,
    String workbookFilename,
    String status,
    String triggerType,
    UUID triggeredBy,
    int rowsRead,
    int committedNew,
    int updatedCount,
    int skippedInvalid,
    int skippedUnchanged,
    int conflictsCount,
    boolean highFailureRate,
    double failureRatePercent,
    OffsetDateTime runAt,
    /** SharePoint's cTag for the version this run read. Opaque; kept for audit/troubleshooting.
     *  Prefer {@link #sharepointRevision} for display. */
    String sharepointVersionId,
    /** SharePoint's content hash for the same version. */
    String quickXorHash,
    /** The numeric revision parsed out of {@code sharepointVersionId} (see {@link SharePointCTag}),
     *  or {@code null} if it didn't match the expected shape. */
    Integer sharepointRevision
) {
    public static IngestionRunAuditResponse from(IngestionRun run) {
        return new IngestionRunAuditResponse(
            run.getId(),
            run.getCohortId(),
            run.getSyncJobId(),
            run.getWorkbookFilename(),
            run.getStatus(),
            run.getTriggerType(),
            run.getTriggeredBy(),
            run.getRowsRead(),
            run.getCommittedNew(),
            run.getUpdatedCount(),
            run.getSkippedInvalid(),
            run.getSkippedUnchanged(),
            run.getConflictsCount(),
            run.isHighFailureRate(),
            run.getFailureRatePercent(),
            run.getRunAt(),
            run.getSharepointVersionId(),
            run.getQuickXorHash(),
            SharePointCTag.parseRevision(run.getSharepointVersionId()));
    }
}
