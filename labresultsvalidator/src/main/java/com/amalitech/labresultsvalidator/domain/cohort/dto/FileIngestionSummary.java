package com.amalitech.labresultsvalidator.domain.cohort.dto;

import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Per-file row-processing counts (B11 AC1) — one entry per workbook the sync run reached.
 * {@code issues} surfaces the actual per-row rejection reasons from {@code errorReportJson} — a
 * count alone ("25 invalid") gives no way to diagnose why.
 */
public record FileIngestionSummary(
    String workbookFilename,
    String status,
    int rowsRead,
    int committedNew,
    int updatedCount,
    int skippedInvalid,
    int skippedUnchanged,
    int conflictsCount,
    OffsetDateTime runAt,
    List<RowIssueSummary> issues
) {
    private static final Logger LOG = LoggerFactory.getLogger(FileIngestionSummary.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static FileIngestionSummary from(IngestionRun run) {
        return new FileIngestionSummary(
            run.getWorkbookFilename(),
            run.getStatus(),
            run.getRowsRead(),
            run.getCommittedNew(),
            run.getUpdatedCount(),
            run.getSkippedInvalid(),
            run.getSkippedUnchanged(),
            run.getConflictsCount(),
            run.getRunAt(),
            parseIssues(run.getErrorReportJson())
        );
    }

    private static List<RowIssueSummary> parseIssues(String errorReportJson) {
        if (errorReportJson == null || errorReportJson.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(errorReportJson, new TypeReference<List<RowIssueSummary>>() { });
        } catch (JsonProcessingException ex) {
            LOG.warn("Could not parse stored errorReportJson: {}", ex.getMessage());
            return List.of();
        }
    }
}
