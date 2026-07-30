package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionRun;
import com.amalitech.labresultsvalidator.domain.cohort.repository.IngestionRunRepository;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemDetails;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Workbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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

        LOG.info("[ingestion] file '{}': read={} new={} updated={} unchanged={} invalid={} conflicts={}",
            fileName, run.getRowsRead(), run.getCommittedNew(), run.getUpdatedCount(),
            run.getSkippedUnchanged(), run.getSkippedInvalid(), run.getConflictsCount());

        return ingestionRunRepository.save(run);
    }

    private String buildErrorReportJson(List<RowError> errors) {
        if (errors.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(errors.stream()
                .map(e -> Map.of(
                    "file", String.valueOf(e.file()),
                    "location", String.valueOf(e.location()),
                    "rule", String.valueOf(e.rule()),
                    "message", String.valueOf(e.message())))
                .collect(Collectors.toList()));
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}
