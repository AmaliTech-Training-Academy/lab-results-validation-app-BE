package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.reference.entity.Lab;
import com.amalitech.labresultsvalidator.domain.reference.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.reference.entity.Learner;
import com.amalitech.labresultsvalidator.domain.reference.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.SpecializationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScoreRowValidationServiceTest {

    private static final String FILE_NAME = "Instructor1.xlsx";
    private static final String SHEET = "Module Setup";
    // Reviewer is matched by full name — InstructorContact has no separate instructor-id column
    // to match on (dropped in V33; it was never sheet-sourced to begin with).
    private static final String REVIEWER_NAME = "Kofi Mensah";

    @Mock
    private LearnerRepository learnerRepository;
    @Mock
    private LabModuleRepository labModuleRepository;
    @Mock
    private LabRepository labRepository;
    @Mock
    private InstructorContactRepository instructorContactRepository;
    @Mock
    private SpecializationRepository specializationRepository;

    private ScoreRowValidationService service;

    private UUID cohortId;
    private UUID specId;
    private UUID moduleId;
    private UUID labId;
    private Learner learner;

    @BeforeEach
    void setUp() {
        service = new ScoreRowValidationService(learnerRepository, labModuleRepository, labRepository,
            instructorContactRepository, specializationRepository);

        cohortId = UUID.randomUUID();
        specId = UUID.randomUUID();
        moduleId = UUID.randomUUID();
        labId = UUID.randomUUID();

        learner = Learner.builder().id(UUID.randomUUID())
            .fullName("Ama Owusu").cohortId(cohortId).specializationId(specId).build();
        when(learnerRepository.findAllByCohortId(cohortId)).thenReturn(List.of(learner));
    }

    private ParsedScoreRow validParsedRow() {
        return new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15", LocalDate.of(2026, 1, 15),
            "Ama Owusu", "REST API Basics", "90", new BigDecimal("90"), REVIEWER_NAME);
    }

    // Resolves purely by (Lab Title, specialization) — mirrors Gate4ScoreSheetValidator. The
    // sheet name plays no role in this lookup.
    private void stubLabUnderSpecialization(UUID theSpecId, String labTitle, UUID theLabId) {
        when(specializationRepository.findAllByCohortId(cohortId)).thenReturn(List.of(
            Specialization.builder().id(theSpecId).cohortId(cohortId).name("Spec").code("SPC").build()));
        LabModule module = LabModule.builder().id(moduleId).specializationId(theSpecId)
            .code("BEM01").name("Backend Fundamentals").build();
        when(labModuleRepository.findAllBySpecializationIdIn(List.of(theSpecId))).thenReturn(List.of(module));
        Lab lab = Lab.builder().id(theLabId).moduleId(moduleId).title(labTitle).build();
        when(labRepository.findAllByModuleIdIn(List.of(moduleId))).thenReturn(List.of(lab));
    }

    @Test
    void validate_validRowWithKnownReviewer_resolvesEverything() {
        stubLabUnderSpecialization(specId, "REST API Basics", labId);
        InstructorContact instructor = InstructorContact.builder().id(UUID.randomUUID())
            .email("kofi.mensah@example.com").fullName(REVIEWER_NAME).build();
        when(instructorContactRepository.findByFullNameIgnoreCase(REVIEWER_NAME)).thenReturn(Optional.of(instructor));

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(validParsedRow()));

        assertThat(result.errors()).isEmpty();
        assertThat(result.validRows()).hasSize(1);
        ValidatedScoreRow row = result.validRows().get(0);
        assertThat(row.learnerId()).isEqualTo(learner.getId());
        assertThat(row.labId()).isEqualTo(labId);
        assertThat(row.instructorContactId()).isEqualTo(instructor.getId());
        assertThat(row.nspName()).isEqualTo("ama owusu");
        assertThat(row.submittedOn()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(row.score()).isEqualTo(new BigDecimal("90.00"));
    }

    @Test
    void validate_arbitrarySheetName_stillResolves_sheetNameIsPurelyCosmetic() {
        stubLabUnderSpecialization(specId, "REST API Basics", labId);
        InstructorContact instructor = InstructorContact.builder().id(UUID.randomUUID())
            .email("kofi.mensah@example.com").fullName(REVIEWER_NAME).build();
        when(instructorContactRepository.findByFullNameIgnoreCase(REVIEWER_NAME)).thenReturn(Optional.of(instructor));

        for (String sheetName : List.of("Module-5", "Sheet1", "Whatever", "Module Setup")) {
            ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, sheetName, 2, "2026-01-15",
                LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "90", new BigDecimal("90"),
                REVIEWER_NAME);

            ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

            assertThat(result.errors()).as("sheet name '%s'", sheetName).isEmpty();
            assertThat(result.validRows()).as("sheet name '%s'", sheetName).hasSize(1);
        }
    }

    @Test
    void validate_unresolvedReviewer_reportsR5ErrorAndDoesNotCommit() {
        // Superseded rule (B6 AC4/B12 AC3): unresolved reviewer used to be non-blocking. It's now a
        // hard failure — a row with no identifiable instructor has nowhere to route a digest to.
        stubLabUnderSpecialization(specId, "REST API Basics", labId);
        when(instructorContactRepository.findByFullNameIgnoreCase(REVIEWER_NAME)).thenReturn(Optional.empty());

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(validParsedRow()));

        assertThat(result.validRows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).rule()).isEqualTo("R5-UNKNOWN-REVIEWER");
        assertThat(result.errors().get(0).instructorContactId()).isNull();
    }

    @Test
    void validate_blankTotalScore_isSkippedSilentlyWithoutAnErrorOrAValidRow() {
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15",
            LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "", null, "INS-001");

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.errors()).isEmpty();
        assertThat(result.validRows()).isEmpty();
    }

    @Test
    void validate_blankNspName_reportsF1Error() {
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15",
            LocalDate.of(2026, 1, 15), "", "REST API Basics", "90", new BigDecimal("90"), "INS-001");

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.validRows()).isEmpty();
        assertThat(result.errors()).anyMatch(e -> "F1-BLANK-NSP".equals(e.rule()));
    }

    @Test
    void validate_unparseableDate_reportsF3Error() {
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "not-a-date", null,
            "Ama Owusu", "REST API Basics", "90", new BigDecimal("90"), "INS-001");

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.errors()).anyMatch(e -> "F3-INVALID-DATE".equals(e.rule()));
    }

    @Test
    void validate_nonNumericScore_reportsF2Error() {
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15",
            LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "abc", null, "INS-001");

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.errors()).anyMatch(e -> "F2-INVALID-SCORE".equals(e.rule()));
    }

    @Test
    void validate_scoreOutOfRange_reportsF2RangeError() {
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15",
            LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "150", new BigDecimal("150"), "INS-001");

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.errors()).anyMatch(e -> "F2-SCORE-OUT-OF-RANGE".equals(e.rule()));
    }

    @Test
    void validate_scoreLooksLikePercentFormattedCell_reportsF2ErrorWithPercentHint() {
        // Mirrors Excel's percent-format trap: an instructor typing "92%" ends up with the sheet
        // cell storing the raw fraction 0.92, which POI reads back as this exact decimal string.
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15",
            LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "0.92", new BigDecimal("0.92"), "INS-001");

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.errors()).anyMatch(e -> "F2-SCORE-NOT-WHOLE-NUMBER".equals(e.rule())
            && e.message().contains("percentage-formatted cell")
            && e.message().contains("92%")
            && e.message().contains("e.g. 92"));
    }

    @Test
    void validate_scoreHasDecimalPointOutsideRoundableRange_reportsF2RangeErrorWithoutPercentHint() {
        // 150.5 is a decimal outside 1-100, and it's neither roundable nor a plausible percent-cell
        // mistake (150.5 x 100 = 15050, also outside 1-100). Being out of range is the actual
        // defect here — not the decimal point (150 or 151 would fail too) — so this reports as
        // F2-SCORE-OUT-OF-RANGE rather than F2-SCORE-NOT-WHOLE-NUMBER, and the hint should not fire.
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15",
            LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "150.5", new BigDecimal("150.5"), "INS-001");

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.errors()).anyMatch(e -> "F2-SCORE-OUT-OF-RANGE".equals(e.rule())
            && !e.message().contains("percentage-formatted cell"));
    }

    @Test
    void validate_scoreWithRoundableDecimal_reportsNoErrorAndRoundsDown() {
        stubLabUnderSpecialization(specId, "REST API Basics", labId);
        InstructorContact instructor = InstructorContact.builder().id(UUID.randomUUID())
            .email("kofi.mensah@example.com").fullName(REVIEWER_NAME).build();
        when(instructorContactRepository.findByFullNameIgnoreCase(REVIEWER_NAME)).thenReturn(Optional.of(instructor));
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15",
            LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "30.2", new BigDecimal("30.2"), REVIEWER_NAME);

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.errors()).isEmpty();
        assertThat(result.validRows()).hasSize(1);
        assertThat(result.validRows().get(0).score()).isEqualTo(new BigDecimal("30.00"));
    }

    @Test
    void validate_scoreWithRoundableDecimal_reportsNoErrorAndRoundsUp() {
        stubLabUnderSpecialization(specId, "REST API Basics", labId);
        InstructorContact instructor = InstructorContact.builder().id(UUID.randomUUID())
            .email("kofi.mensah@example.com").fullName(REVIEWER_NAME).build();
        when(instructorContactRepository.findByFullNameIgnoreCase(REVIEWER_NAME)).thenReturn(Optional.of(instructor));
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15",
            LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "30.6", new BigDecimal("30.6"), REVIEWER_NAME);

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.errors()).isEmpty();
        assertThat(result.validRows()).hasSize(1);
        assertThat(result.validRows().get(0).score()).isEqualTo(new BigDecimal("31.00"));
    }

    @Test
    void validate_scoreWithExactHalfDecimal_roundsHalfUp() {
        stubLabUnderSpecialization(specId, "REST API Basics", labId);
        InstructorContact instructor = InstructorContact.builder().id(UUID.randomUUID())
            .email("kofi.mensah@example.com").fullName(REVIEWER_NAME).build();
        when(instructorContactRepository.findByFullNameIgnoreCase(REVIEWER_NAME)).thenReturn(Optional.of(instructor));
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15",
            LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "30.5", new BigDecimal("30.5"), REVIEWER_NAME);

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.errors()).isEmpty();
        assertThat(result.validRows()).hasSize(1);
        assertThat(result.validRows().get(0).score()).isEqualTo(new BigDecimal("31.00"));
    }

    @Test
    void validate_scoreZero_reportsF2RangeError() {
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15",
            LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "0", new BigDecimal("0"), "INS-001");

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.errors()).anyMatch(e -> "F2-SCORE-OUT-OF-RANGE".equals(e.rule()));
    }

    @Test
    void validate_unknownNsp_reportsR1Error() {
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15",
            LocalDate.of(2026, 1, 15), "Not A Learner", "REST API Basics", "90", new BigDecimal("90"), "INS-001");

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.errors()).anyMatch(e -> "R1-UNKNOWN-NSP".equals(e.rule()));
    }

    @Test
    void validate_unknownLabTitle_reportsR4Error() {
        when(specializationRepository.findAllByCohortId(cohortId)).thenReturn(List.of(
            Specialization.builder().id(specId).cohortId(cohortId).name("Spec").code("SPC").build()));
        when(labModuleRepository.findAllBySpecializationIdIn(List.of(specId))).thenReturn(List.of());
        when(labRepository.findAllByModuleIdIn(List.of())).thenReturn(List.of());

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(validParsedRow()));

        assertThat(result.errors()).anyMatch(e -> "R4-UNKNOWN-LAB".equals(e.rule()));
    }

    @Test
    void validate_labTitleExistsUnderADifferentSpecialization_reportsSpecMismatchError() {
        UUID otherSpecId = UUID.randomUUID();
        // The learner is in `specId`, but "REST API Basics" is only configured under `otherSpecId`.
        stubLabUnderSpecialization(otherSpecId, "REST API Basics", labId);

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(validParsedRow()));

        assertThat(result.errors()).anyMatch(e -> "R4-LAB-SPEC-MISMATCH".equals(e.rule()));
    }
}
