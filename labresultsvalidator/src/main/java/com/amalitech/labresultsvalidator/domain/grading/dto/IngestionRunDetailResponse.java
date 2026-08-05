package com.amalitech.labresultsvalidator.domain.grading.dto;

import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.UUID;

/** D5 AC2 — single-run detail, including the parsed row-level {@code errorReportJson}. */
public record IngestionRunDetailResponse(
    UUID id,
    UUID cohortId,
    String workbookFilename,
    String sharepointFileUrl,
    String sharepointVersionId,
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
    Object errorReport,
    OffsetDateTime runAt
) {
    private static final Logger LOG = LoggerFactory.getLogger(IngestionRunDetailResponse.class);

    public static IngestionRunDetailResponse from(IngestionRun run, ObjectMapper objectMapper) {
        return new IngestionRunDetailResponse(
            run.getId(),
            run.getCohortId(),
            run.getWorkbookFilename(),
            run.getSharepointFileUrl(),
            run.getSharepointVersionId(),
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
            parseJson(run.getErrorReportJson(), objectMapper),
            run.getRunAt());
    }

    private static Object parseJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            LOG.warn("Failed to parse errorReportJson: {}", ex.getMessage());
            return json;
        }
    }
}
