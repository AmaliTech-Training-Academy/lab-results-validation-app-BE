package com.amalitech.labresultsvalidator.domain.grading.dto;

import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Per-file row-processing counts (B11 AC1) — one entry per workbook the sync run reached.
 * {@code issues} surfaces the actual per-row rejection reasons from {@code errorReportJson} — a
 * count alone ("25 invalid") gives no way to diagnose why. {@code rejectionReasons} rolls the
 * same data up by rule code, so a high-failure-rate file's "why" is readable at a glance instead
 * of scanning every row.
 */
public record FileIngestionSummary(
    String workbookFilename,
    String status,
    /** SharePoint's cTag for the version this run read — lets an admin confirm a since-edited
     *  file was actually re-fetched, not stale. Populated for every file, including skipped ones. */
    String sharepointVersionId,
    /** SharePoint's content hash for the same version — distinguishes a real re-save from a
     *  metadata-only touch (cTag changed, bytes didn't). */
    String quickXorHash,
    int rowsRead,
    int committedNew,
    int updatedCount,
    int skippedInvalid,
    int skippedUnchanged,
    int conflictsCount,
    boolean highFailureRate,
    double failureRatePercent,
    OffsetDateTime runAt,
    List<RowIssueSummary> issues,
    List<RejectionReasonSummary> rejectionReasons
) {
    private static final Logger LOG = LoggerFactory.getLogger(FileIngestionSummary.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static FileIngestionSummary from(IngestionRun run) {
        List<RowIssueSummary> issues = parseIssues(run.getErrorReportJson());
        return new FileIngestionSummary(
            run.getWorkbookFilename(),
            run.getStatus(),
            run.getSharepointVersionId(),
            run.getQuickXorHash(),
            run.getRowsRead(),
            run.getCommittedNew(),
            run.getUpdatedCount(),
            run.getSkippedInvalid(),
            run.getSkippedUnchanged(),
            run.getConflictsCount(),
            run.isHighFailureRate(),
            run.getFailureRatePercent(),
            run.getRunAt(),
            issues,
            summarizeReasons(issues)
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

    private static List<RejectionReasonSummary> summarizeReasons(List<RowIssueSummary> issues) {
        Map<String, Long> counts = issues.stream()
            .collect(Collectors.groupingBy(RowIssueSummary::rule, Collectors.counting()));
        return counts.entrySet().stream()
            .map(e -> new RejectionReasonSummary(
                e.getKey(), RejectionRuleDescriptions.describe(e.getKey()), e.getValue()))
            .sorted(Comparator.comparingLong(RejectionReasonSummary::count).reversed()
                .thenComparing(RejectionReasonSummary::rule))
            .toList();
    }
}
