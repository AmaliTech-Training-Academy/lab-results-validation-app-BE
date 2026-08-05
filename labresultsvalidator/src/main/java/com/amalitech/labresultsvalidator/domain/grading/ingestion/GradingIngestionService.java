package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionRun;
import com.amalitech.labresultsvalidator.domain.grading.repository.IngestionRunRepository;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemDetails;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Workbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates B5-B9 for one changed/new grading workbook: parse -> validate -> classify ->
 * commit, persisting one {@link IngestionRun} audit row per file (B11 AC1). Called from the
 * {@code CohortSyncJobRunner} seam, before the S3 archive write.
 */
@Component
public class GradingIngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(GradingIngestionService.class);

    private final ScoreRowParser scoreRowParser;
    private final ScoreRowValidationService validationService;
    private final ScoreRowClassifier classifier;
    private final LabResultCommitService commitService;
    private final IngestionRunRepository ingestionRunRepository;
    private final ObjectMapper objectMapper;

    public GradingIngestionService(
        ScoreRowParser scoreRowParser,
        ScoreRowValidationService validationService,
        ScoreRowClassifier classifier,
        LabResultCommitService commitService,
        IngestionRunRepository ingestionRunRepository,
        ObjectMapper objectMapper
    ) {
        this.scoreRowParser = scoreRowParser;
        this.validationService = validationService;
        this.classifier = classifier;
        this.commitService = commitService;
        this.ingestionRunRepository = ingestionRunRepository;
        this.objectMapper = objectMapper;
    }

    public IngestionRun process(Cohort cohort, UUID syncJobId, String fileName, Workbook workbook,
                                DriveItemDetails details, String fileSha256Hex,
                                UUID triggeredBy, String triggerType) {
        IngestionRun run = ingestionRunRepository.save(IngestionRun.builder()
            .cohortId(cohort.getId())
            .syncJobId(syncJobId)
            .workbookFilename(fileName)
            .sharepointFileUrl(details == null ? null : details.webUrl())
            .sharepointVersionId(details == null ? null : details.versionId())
            .quickXorHash(details == null ? null : details.quickXorHash())
            .fileSha256(fileSha256Hex)
            .triggeredBy(triggeredBy)
            .triggerType(triggerType)
            .build());

        ScoreRowParser.SheetParseResult parsed = scoreRowParser.parse(fileName, workbook);
        ScoreRowValidationService.ValidationResult validated =
            validationService.validate(cohort.getId(), parsed.rows());
        List<RowClassification> classifications = classifier.classify(validated.validRows());
        LabResultCommitService.CommitOutcome commitOutcome =
            commitService.commit(classifications, cohort.getId(), run.getId(), triggeredBy);

        List<RowError> allErrors = new ArrayList<>();
        allErrors.addAll(parsed.errors());
        allErrors.addAll(validated.errors());
        allErrors.addAll(commitOutcome.rowErrors());

        run.setRowsRead(parsed.rows().size());
        run.setCommittedNew(commitOutcome.committedNew());
        run.setUpdatedCount(commitOutcome.updatedCount());
        run.setSkippedInvalid(validated.errors().size() + parsed.errors().size() + commitOutcome.skippedInvalid());
        run.setSkippedUnchanged(commitOutcome.skippedUnchanged());
        run.setConflictsCount(commitOutcome.conflictsCount());
        run.setStatus(allErrors.isEmpty() ? "completed" : "partial");
        run.setErrorReportJson(buildErrorReportJson(allErrors));

        // B7 AC3 / §4.5 — "READY rows" are rows that actually reached F/R validation (a blank
        // Total Score is skipped silently at B6 AC1 and never counts toward either side of this
        // ratio). A row that failed validation, or a valid row that failed at commit time
        // (COMMIT-FAILED), counts as rejected; duplicates/unchanged rows passed validation and
        // are not rejections.
        int readyRows = validated.validRows().size() + validated.errors().size();
        int rejectedRows = validated.errors().size() + commitOutcome.skippedInvalid();
        double failureRate = readyRows == 0 ? 0.0 : (double) rejectedRows / readyRows;
        run.setFailureRatePercent(failureRate * 100);
        run.setHighFailureRate(failureRate > 0.5);

        LOG.info("[ingestion] file '{}': read={} new={} updated={} unchanged={} invalid={} conflicts={} "
                + "failureRate={}%",
            fileName, run.getRowsRead(), run.getCommittedNew(), run.getUpdatedCount(),
            run.getSkippedUnchanged(), run.getSkippedInvalid(), run.getConflictsCount(),
            String.format("%.1f", run.getFailureRatePercent()));
        if (run.isHighFailureRate()) {
            LOG.warn("[ingestion] file '{}': HIGH FAILURE RATE {}% ({} of {} READY rows rejected)",
                fileName, String.format("%.1f", run.getFailureRatePercent()), rejectedRows, readyRows);
        }

        return ingestionRunRepository.save(run);
    }

    /**
     * Persists the audit row for a file the hash short-circuit deduped (D1 AC2 / D4 AC2) — a
     * single {@code skipped} row so "we saw it, nothing changed" is auditable rather than the
     * file being silently absent from {@code ingestion_runs}.
     */
    public IngestionRun recordSkipped(Cohort cohort, UUID syncJobId, String fileName,
                                      DriveItemDetails details, String fileSha256Hex,
                                      UUID triggeredBy, String triggerType) {
        return ingestionRunRepository.save(IngestionRun.builder()
            .cohortId(cohort.getId())
            .syncJobId(syncJobId)
            .workbookFilename(fileName)
            .sharepointFileUrl(details == null ? null : details.webUrl())
            .sharepointVersionId(details == null ? null : details.versionId())
            .quickXorHash(details == null ? null : details.quickXorHash())
            .fileSha256(fileSha256Hex)
            .triggeredBy(triggeredBy)
            .triggerType(triggerType)
            .status("skipped")
            .build());
    }

    private String buildErrorReportJson(List<RowError> errors) {
        if (errors.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(errors.stream()
                .map(e -> {
                    // LinkedHashMap, not Map.of — instructorContactId is routinely null (routes
                    // the error to the admin digest instead of an instructor's), and Map.of
                    // rejects null values. Left as a real UUID/null (not String.valueOf'd) so it
                    // deserializes back into RowIssueSummary.instructorContactId as a proper UUID
                    // or JSON null, not the literal string "null".
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("file", String.valueOf(e.file()));
                    entry.put("location", String.valueOf(e.location()));
                    entry.put("rule", String.valueOf(e.rule()));
                    entry.put("message", String.valueOf(e.message()));
                    entry.put("instructorContactId", e.instructorContactId());
                    entry.put("labTitle", e.labTitle());
                    return entry;
                })
                .collect(Collectors.toList()));
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}
