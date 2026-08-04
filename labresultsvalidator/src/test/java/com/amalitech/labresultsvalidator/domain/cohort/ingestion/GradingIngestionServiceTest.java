package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionRun;
import com.amalitech.labresultsvalidator.domain.cohort.repository.IngestionRunRepository;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GradingIngestionServiceTest {

    @Mock
    private ScoreRowParser scoreRowParser;
    @Mock
    private ScoreRowValidationService validationService;
    @Mock
    private ScoreRowClassifier classifier;
    @Mock
    private LabResultCommitService commitService;
    @Mock
    private IngestionRunRepository ingestionRunRepository;

    private GradingIngestionService service;

    private Cohort cohort;
    private UUID syncJobId;
    private UUID triggeredBy;
    private Workbook workbook;
    private DriveItemDetails details;

    @BeforeEach
    void setUp() {
        service = new GradingIngestionService(
            scoreRowParser, validationService, classifier, commitService, ingestionRunRepository,
            new ObjectMapper());

        cohort = Cohort.builder().id(UUID.randomUUID()).name("Test Cohort").build();
        syncJobId = UUID.randomUUID();
        triggeredBy = UUID.randomUUID();
        workbook = mock(Workbook.class);
        details = new DriveItemDetails("Instructor1.xlsx", "Lab Scores", "quickxor-1", "cTag-1", 1024L,
            "https://sp/Instructor1.xlsx");

        // Every save echoes back what was passed, assigning an id on first save.
        when(ingestionRunRepository.save(any())).thenAnswer(invocation -> {
            IngestionRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(UUID.randomUUID());
            }
            return run;
        });
    }

    private ParsedScoreRow parsedRow() {
        return parsedRowAt(2);
    }

    private ParsedScoreRow parsedRowAt(int rowNum) {
        return new ParsedScoreRow("Instructor1.xlsx", "BEM01", rowNum, "2026-01-15",
            LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "0.9", new BigDecimal("0.9"), "INS-001");
    }

    private ValidatedScoreRow validatedRow() {
        return new ValidatedScoreRow("Instructor1.xlsx", "BEM01", 2, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "ama owusu", LocalDate.of(2026, 1, 15), new BigDecimal("90.00"));
    }

    @Test
    void process_noErrors_savesACompletedRunWithCounts() {
        ValidatedScoreRow validated = validatedRow();
        when(scoreRowParser.parse("Instructor1.xlsx", workbook))
            .thenReturn(new ScoreRowParser.SheetParseResult(List.of(parsedRow()), List.of()));
        when(validationService.validate(cohort.getId(), List.of(parsedRow())))
            .thenReturn(new ScoreRowValidationService.ValidationResult(List.of(validated), List.of()));
        RowClassification classification = new RowClassification(ClassificationKind.NEW, validated, null);
        when(classifier.classify(List.of(validated))).thenReturn(List.of(classification));
        when(commitService.commit(any(), any(), any(), any()))
            .thenReturn(new LabResultCommitService.CommitOutcome(1, 0, 0, 0, 0, List.of()));

        IngestionRun run = service.process(cohort, syncJobId, "Instructor1.xlsx", workbook, details, "sha-1",
            triggeredBy, "SCHEDULED");

        assertThat(run.getStatus()).isEqualTo("completed");
        assertThat(run.getRowsRead()).isEqualTo(1);
        assertThat(run.getCommittedNew()).isEqualTo(1);
        assertThat(run.getCohortId()).isEqualTo(cohort.getId());
        assertThat(run.getSyncJobId()).isEqualTo(syncJobId);
        assertThat(run.getWorkbookFilename()).isEqualTo("Instructor1.xlsx");
        assertThat(run.getSharepointFileUrl()).isEqualTo("https://sp/Instructor1.xlsx");
        assertThat(run.getFileSha256()).isEqualTo("sha-1");
        assertThat(run.getTriggeredBy()).isEqualTo(triggeredBy);
        assertThat(run.getTriggerType()).isEqualTo("SCHEDULED");
        assertThat(run.getErrorReportJson()).isNull();

        verify(ingestionRunRepository, times(2)).save(any());
    }

    @Test
    void process_withRowErrors_savesAPartialRunWithErrorReportJson() {
        RowError parseError =
            new RowError("Instructor1.xlsx", "sheet BEM02", "S2-MISSING-COLUMN", "missing reviewer", null);
        when(scoreRowParser.parse("Instructor1.xlsx", workbook))
            .thenReturn(new ScoreRowParser.SheetParseResult(List.of(), List.of(parseError)));
        when(validationService.validate(cohort.getId(), List.of()))
            .thenReturn(new ScoreRowValidationService.ValidationResult(List.of(), List.of()));
        when(classifier.classify(List.of())).thenReturn(List.of());
        when(commitService.commit(any(), any(), any(), any()))
            .thenReturn(new LabResultCommitService.CommitOutcome(0, 0, 0, 0, 0, List.of()));

        IngestionRun run = service.process(cohort, syncJobId, "Instructor1.xlsx", workbook, details, "sha-1",
            triggeredBy, "SCHEDULED");

        assertThat(run.getStatus()).isEqualTo("partial");
        assertThat(run.getErrorReportJson()).contains("S2-MISSING-COLUMN").contains("missing reviewer");
    }

    @Test
    void process_overHalfOfReadyRowsRejected_flagsHighFailureRate() {
        ValidatedScoreRow validated = validatedRow();
        List<ParsedScoreRow> rows = List.of(parsedRowAt(2), parsedRowAt(3), parsedRowAt(4));
        List<RowError> errors = List.of(
            new RowError("Instructor1.xlsx", "row 3", "R1-UNKNOWN-NSP", "unknown", null),
            new RowError("Instructor1.xlsx", "row 4", "F2-INVALID-SCORE", "not numeric", null));
        when(scoreRowParser.parse("Instructor1.xlsx", workbook))
            .thenReturn(new ScoreRowParser.SheetParseResult(rows, List.of()));
        when(validationService.validate(cohort.getId(), rows))
            .thenReturn(new ScoreRowValidationService.ValidationResult(List.of(validated), errors));
        RowClassification classification = new RowClassification(ClassificationKind.NEW, validated, null);
        when(classifier.classify(List.of(validated))).thenReturn(List.of(classification));
        when(commitService.commit(any(), any(), any(), any()))
            .thenReturn(new LabResultCommitService.CommitOutcome(1, 0, 0, 0, 0, List.of()));

        IngestionRun run = service.process(cohort, syncJobId, "Instructor1.xlsx", workbook, details, "sha-1",
            triggeredBy, "SCHEDULED");

        // 2 of 3 READY rows rejected = 66.7% — over the 50% threshold (B7 AC3).
        assertThat(run.isHighFailureRate()).isTrue();
        assertThat(run.getFailureRatePercent()).isCloseTo(66.7, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void process_atOrBelowHalfOfReadyRowsRejected_doesNotFlagHighFailureRate() {
        ValidatedScoreRow validated = validatedRow();
        List<ParsedScoreRow> rows = List.of(parsedRowAt(2), parsedRowAt(3), parsedRowAt(4));
        List<RowError> errors = List.of(
            new RowError("Instructor1.xlsx", "row 3", "R1-UNKNOWN-NSP", "unknown", null));
        when(scoreRowParser.parse("Instructor1.xlsx", workbook))
            .thenReturn(new ScoreRowParser.SheetParseResult(rows, List.of()));
        when(validationService.validate(cohort.getId(), rows))
            .thenReturn(new ScoreRowValidationService.ValidationResult(List.of(validated), errors));
        RowClassification classification = new RowClassification(ClassificationKind.NEW, validated, null);
        when(classifier.classify(List.of(validated))).thenReturn(List.of(classification));
        when(commitService.commit(any(), any(), any(), any()))
            .thenReturn(new LabResultCommitService.CommitOutcome(1, 0, 0, 0, 0, List.of()));

        IngestionRun run = service.process(cohort, syncJobId, "Instructor1.xlsx", workbook, details, "sha-1",
            triggeredBy, "SCHEDULED");

        // 1 of 3 READY rows rejected = 33.3% — under the threshold.
        assertThat(run.isHighFailureRate()).isFalse();
    }

    @Test
    void process_savesTheInitialRunBeforeRunningTheRestOfThePipeline() {
        when(scoreRowParser.parse(any(), any()))
            .thenReturn(new ScoreRowParser.SheetParseResult(List.of(), List.of()));
        when(validationService.validate(any(), any()))
            .thenReturn(new ScoreRowValidationService.ValidationResult(List.of(), List.of()));
        when(classifier.classify(any())).thenReturn(List.of());
        when(commitService.commit(any(), any(), any(), any()))
            .thenReturn(new LabResultCommitService.CommitOutcome(0, 0, 0, 0, 0, List.of()));

        service.process(cohort, syncJobId, "Instructor1.xlsx", workbook, details, "sha-1", triggeredBy, "SCHEDULED");

        ArgumentCaptor<UUID> runIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(commitService).commit(any(), any(), runIdCaptor.capture(), any());
        assertThat(runIdCaptor.getValue()).isNotNull();
    }

    @Test
    void recordSkipped_savesASingleSkippedRunWithProvenanceAndNoRowActivity() {
        IngestionRun run = service.recordSkipped(cohort, syncJobId, "Instructor1.xlsx", details, "sha-1",
            triggeredBy, "SCHEDULED");

        assertThat(run.getStatus()).isEqualTo("skipped");
        assertThat(run.getCohortId()).isEqualTo(cohort.getId());
        assertThat(run.getSyncJobId()).isEqualTo(syncJobId);
        assertThat(run.getWorkbookFilename()).isEqualTo("Instructor1.xlsx");
        assertThat(run.getSharepointFileUrl()).isEqualTo("https://sp/Instructor1.xlsx");
        assertThat(run.getSharepointVersionId()).isEqualTo("cTag-1");
        assertThat(run.getQuickXorHash()).isEqualTo("quickxor-1");
        assertThat(run.getFileSha256()).isEqualTo("sha-1");
        assertThat(run.getTriggeredBy()).isEqualTo(triggeredBy);
        assertThat(run.getTriggerType()).isEqualTo("SCHEDULED");
        assertThat(run.getRowsRead()).isZero();
        assertThat(run.getErrorReportJson()).isNull();

        verify(ingestionRunRepository, times(1)).save(any());
    }
}
