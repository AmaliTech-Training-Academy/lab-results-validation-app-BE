package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorSpecializationAssignment;
import com.amalitech.labresultsvalidator.domain.reference.entity.Lab;
import com.amalitech.labresultsvalidator.domain.reference.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.reference.entity.Learner;
import com.amalitech.labresultsvalidator.domain.reference.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorSpecializationAssignmentRepository;
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
    private InstructorSpecializationAssignmentRepository instructorSpecializationAssignmentRepository;
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
            instructorContactRepository, instructorSpecializationAssignmentRepository, specializationRepository);

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
            "Ama Owusu", "REST API Basics", "0.9", new BigDecimal("0.9"), REVIEWER_NAME);
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

    // Reviewer resolution is scoped to instructors assigned to this cohort's own specializations
    // (via instructor_specialization_assignments) — a name match alone isn't enough.
    private void stubInstructorAssignedToCohort(UUID theSpecId, UUID instructorId) {
        when(instructorSpecializationAssignmentRepository.findAllBySpecializationIdIn(List.of(theSpecId)))
            .thenReturn(List.of(InstructorSpecializationAssignment.builder()
                .id(UUID.randomUUID())
                .instructorContactId(instructorId)
                .specializationId(theSpecId)
                .build()));
    }

    @Test
    void validate_validRowWithKnownReviewer_resolvesEverything() {
        stubLabUnderSpecialization(specId, "REST API Basics", labId);
        InstructorContact instructor = InstructorContact.builder().id(UUID.randomUUID())
            .email("kofi.mensah@example.com").fullName(REVIEWER_NAME).build();
        when(instructorContactRepository.findByFullNameIgnoreCase(REVIEWER_NAME)).thenReturn(Optional.of(instructor));
        stubInstructorAssignedToCohort(specId, instructor.getId());

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
        stubInstructorAssignedToCohort(specId, instructor.getId());

        for (String sheetName : List.of("Module-5", "Sheet1", "Whatever", "Module Setup")) {
            ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, sheetName, 2, "2026-01-15",
                LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "0.9", new BigDecimal("0.9"),
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
    void validate_reviewerNameMatchesAnInstructorFromAnotherCohort_reportsR5ErrorInstead() {
        // instructor_contacts is global — a name can match a real InstructorContact who has never
        // taught in this cohort (e.g. only assigned in a different cohort, or a same-named instructor
        // there). Resolution must not trust the name match alone: it has to be scoped to instructors
        // actually assigned to this cohort's own specializations.
        stubLabUnderSpecialization(specId, "REST API Basics", labId);
        InstructorContact instructor = InstructorContact.builder().id(UUID.randomUUID())
            .email("kofi.mensah@example.com").fullName(REVIEWER_NAME).build();
        when(instructorContactRepository.findByFullNameIgnoreCase(REVIEWER_NAME)).thenReturn(Optional.of(instructor));
        // No stubInstructorAssignedToCohort call — this instructor is not assigned to `specId`.

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
            LocalDate.of(2026, 1, 15), "", "REST API Basics", "0.9", new BigDecimal("0.9"), "INS-001");

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.validRows()).isEmpty();
        assertThat(result.errors()).anyMatch(e -> "F1-BLANK-NSP".equals(e.rule()));
    }

    @Test
    void validate_unparseableDate_reportsF3Error() {
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "not-a-date", null,
            "Ama Owusu", "REST API Basics", "0.9", new BigDecimal("0.9"), "INS-001");

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
            LocalDate.of(2026, 1, 15), "Ama Owusu", "REST API Basics", "1.5", new BigDecimal("1.5"), "INS-001");

        ScoreRowValidationService.ValidationResult result = service.validate(cohortId, List.of(row));

        assertThat(result.errors()).anyMatch(e -> "F2-SCORE-OUT-OF-RANGE".equals(e.rule()));
    }

    @Test
    void validate_unknownNsp_reportsR1Error() {
        ParsedScoreRow row = new ParsedScoreRow(FILE_NAME, SHEET, 2, "2026-01-15",
            LocalDate.of(2026, 1, 15), "Not A Learner", "REST API Basics", "0.9", new BigDecimal("0.9"), "INS-001");

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
