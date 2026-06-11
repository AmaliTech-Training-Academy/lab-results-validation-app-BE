package com.amalitech.labresultsvalidator.domain.lab_result.service;

import com.amalitech.labresultsvalidator.common.csv.CsvParseResult;
import com.amalitech.labresultsvalidator.common.csv.CsvParserService;
import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import com.amalitech.labresultsvalidator.common.csv.CsvWriterService;
import com.amalitech.labresultsvalidator.common.csv.MalformedCsvException;
import com.amalitech.labresultsvalidator.common.csv.ParsedRow;
import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.domain.csvUploads.entity.CsvUpload;
import com.amalitech.labresultsvalidator.domain.csvUploads.repository.CsvUploadRepository;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.enums.LearnerStatus;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.lab.entity.Lab;
import com.amalitech.labresultsvalidator.domain.lab.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultCsvRow;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultUploadResponse;
import com.amalitech.labresultsvalidator.domain.lab_result.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.lab_result.repository.LabResultRepository;
import com.amalitech.labresultsvalidator.domain.learner.entity.Learner;
import com.amalitech.labresultsvalidator.domain.learner.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import com.amalitech.labresultsvalidator.domain.module.repository.ModuleRepository;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.repository.UserModuleAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabResultUploadServiceTest {

    @Mock private CsvParserService csvParserService;
    @Mock private CsvWriterService csvWriterService;
    @Mock private CsvUploadRepository csvUploadRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private LearnerRepository learnerRepository;
    @Mock private ModuleRepository moduleRepository;
    @Mock private LabRepository labRepository;
    @Mock private UserModuleAssignmentRepository userModuleAssignmentRepository;

    @InjectMocks
    private LabResultUploadService service;

    private User instructor;
    private User admin;
    private Cohort cohort;
    private Specialization specialization;
    private Module module;
    private Lab lab;
    private Learner learner;

    @BeforeEach
    void setUp() {
        instructor = User.builder().id(UUID.randomUUID()).email("inst@test.com")
            .passwordHash("h").role(UserRole.INSTRUCTOR).build();
        admin = User.builder().id(UUID.randomUUID()).email("admin@test.com")
            .passwordHash("h").role(UserRole.ADMIN).build();

        cohort = Cohort.builder().id(UUID.randomUUID()).name("Cohort 1").active(true).build();
        specialization = Specialization.builder().id(UUID.randomUUID())
            .name("Data Analytics").cohort(cohort).build();
        module = Module.builder().id(UUID.randomUUID()).name("Module 1")
            .specialization(specialization).sequence(1).build();
        lab = Lab.builder().id(UUID.randomUUID()).title("Lab 1")
            .maxScore(new BigDecimal("20.00")).module(module).build();
        learner = Learner.builder().id(UUID.randomUUID()).fullName("Jane Doe")
            .email("jane@test.com").cohort(cohort).specialization(specialization)
            .status(LearnerStatus.ACTIVE).build();

        // Default happy-path referential stubs; individual tests override as needed.
        when(learnerRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(learner));
        when(moduleRepository.findBySpecializationIdAndNameIgnoreCase(any(), anyString()))
            .thenReturn(Optional.of(module));
        when(labRepository.findByModuleIdAndTitleIgnoreCase(any(), anyString()))
            .thenReturn(Optional.of(lab));
        when(userModuleAssignmentRepository.existsByUserIdAndModuleId(any(), any())).thenReturn(true);
        when(labResultRepository.findByLearnerIdAndLabIdAndAttemptNumber(any(), any(), anyShort()))
            .thenReturn(Optional.empty());
        when(csvUploadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        setActor(instructor);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── V1/V2 structural ───────────────────────────────────────────────────────

    @Test
    void bulkUpload_withMalformedCsv_throwsAndPersistsNothing() {
        when(csvParserService.parse(any(), any()))
            .thenThrow(new MalformedCsvException("CSV is missing required column(s)"));

        assertThatThrownBy(() -> service.bulkUpload(file()))
            .isInstanceOf(MalformedCsvException.class);

        verify(labResultRepository, never()).saveAll(any());
        verify(csvUploadRepository, never()).save(any());
    }

    // ── Dedup gate (409) ─────────────────────────────────────────────────────────

    @Test
    void bulkUpload_withDuplicateFile_throwsDuplicateResource() {
        CsvUpload prior = CsvUpload.builder()
            .id(UUID.randomUUID()).fileSha256("abc")
            .uploadedAt(java.time.OffsetDateTime.now()).build();
        when(csvUploadRepository.findByFileSha256(anyString())).thenReturn(Optional.of(prior));

        assertThatThrownBy(() -> service.bulkUpload(file()))
            .isInstanceOf(DuplicateResourceException.class)
            .hasMessageContaining("already uploaded");

        verify(csvParserService, never()).parse(any(), any());
        verify(csvUploadRepository, never()).save(any());
    }

    // ── Happy path + audit counts ──────────────────────────────────────────────

    @Test
    void bulkUpload_withAllValidRows_insertsAllWithZeroErrors() {
        doReturn(parsed(validRow())).when(csvParserService).parse(any(), any());

        LabResultUploadResponse result = service.bulkUpload(file());

        assertThat(result.getInsertedCount()).isEqualTo(1);
        assertThat(result.getRejectedCount()).isZero();
        assertThat(result.getErrors()).isEmpty();

        ArgumentCaptor<CsvUpload> captor = ArgumentCaptor.forClass(CsvUpload.class);
        verify(csvUploadRepository).save(captor.capture());
        assertThat(captor.getValue().getTotalRows()).isEqualTo(1);
        assertThat(captor.getValue().getAcceptedRows()).isEqualTo(1);
        assertThat(captor.getValue().getRejectedRows()).isZero();
    }

    @Test
    void bulkUpload_withParserBindingError_reportsItAlongsideValidRow() {
        CsvRowError binding = new CsvRowError(3L, null, "could not be parsed");
        CsvParseResult<LabResultCsvRow> parsed = new CsvParseResult<>(
            List.of(new ParsedRow<>(2L, validRow())), List.of(binding));
        doReturn(parsed).when(csvParserService).parse(any(), any());

        LabResultUploadResponse result = service.bulkUpload(file());

        assertThat(result.getInsertedCount()).isEqualTo(1);
        assertThat(result.getRejectedCount()).isEqualTo(1);
    }

    // ── Field-level (V3–V8) ──────────────────────────────────────────────────────

    @Test
    void bulkUpload_withBlankLabTitle_rejectsV3() {
        LabResultCsvRow row = validRow();
        row.setLabTitle("  ");
        doReturn(parsed(row)).when(csvParserService).parse(any(), any());

        assertSingleError(service.bulkUpload(file()), "LAB_TITLE", "V3");
    }

    @Test
    void bulkUpload_withInvalidEmail_rejectsV8() {
        LabResultCsvRow row = validRow();
        row.setLearnerEmail("not-an-email");
        doReturn(parsed(row)).when(csvParserService).parse(any(), any());

        assertHasError(service.bulkUpload(file()), "LEARNER_EMAIL", "V8");
    }

    @Test
    void bulkUpload_withNonNumericScore_rejectsV4() {
        LabResultCsvRow row = validRow();
        row.setScore("abc");
        doReturn(parsed(row)).when(csvParserService).parse(any(), any());

        assertHasError(service.bulkUpload(file()), "SCORE", "V4");
    }

    @Test
    void bulkUpload_withScoreAboveMax_rejectsV5() {
        LabResultCsvRow row = validRow();
        row.setScore("25");
        row.setMaxScore("20");
        doReturn(parsed(row)).when(csvParserService).parse(any(), any());

        assertHasError(service.bulkUpload(file()), "SCORE", "V5");
    }

    @Test
    void bulkUpload_withAttemptOutOfRange_rejectsV6() {
        LabResultCsvRow row = validRow();
        row.setAttemptNumber("3");
        doReturn(parsed(row)).when(csvParserService).parse(any(), any());

        assertHasError(service.bulkUpload(file()), "ATTEMPT_NUMBER", "V6");
    }

    @Test
    void bulkUpload_withNonIsoDate_rejectsV7() {
        LabResultCsvRow row = validRow();
        row.setSubmittedOn("30-05-2026");
        doReturn(parsed(row)).when(csvParserService).parse(any(), any());

        assertHasError(service.bulkUpload(file()), "SUBMITTED_ON", "V7");
    }

    // ── In-file duplicate (V16) ──────────────────────────────────────────────────

    @Test
    void bulkUpload_withInFileDuplicate_rejectsBothV16() {
        LabResultCsvRow a = validRow();
        LabResultCsvRow b = validRow();
        doReturn(parsed(a, b)).when(csvParserService).parse(any(), any());

        LabResultUploadResponse result = service.bulkUpload(file());

        assertThat(result.getInsertedCount()).isZero();
        assertThat(result.getRejectedCount()).isEqualTo(2);
        assertThat(result.getErrors()).extracting(CsvRowError::rule).allMatch("V16"::equals);
    }

    // ── Referential (V9–V15) ─────────────────────────────────────────────────────

    @Test
    void bulkUpload_withUnknownLearner_rejectsV9() {
        when(learnerRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        doReturn(parsed(validRow())).when(csvParserService).parse(any(), any());

        assertSingleError(service.bulkUpload(file()), "LEARNER_EMAIL", "V9");
    }

    @Test
    void bulkUpload_withArchivedLearner_rejectsV9() {
        learner.setStatus(LearnerStatus.ARCHIVED);
        doReturn(parsed(validRow())).when(csvParserService).parse(any(), any());

        assertSingleError(service.bulkUpload(file()), "LEARNER_EMAIL", "V9");
    }

    @Test
    void bulkUpload_withCohortMismatch_rejectsV10() {
        LabResultCsvRow row = validRow();
        row.setCohortName("Other Cohort");
        doReturn(parsed(row)).when(csvParserService).parse(any(), any());

        assertSingleError(service.bulkUpload(file()), "COHORT_NAME", "V10");
    }

    @Test
    void bulkUpload_withSpecializationMismatch_rejectsV11() {
        LabResultCsvRow row = validRow();
        row.setSpecializationName("Other Track");
        doReturn(parsed(row)).when(csvParserService).parse(any(), any());

        assertSingleError(service.bulkUpload(file()), "SPECIALIZATION_NAME", "V11");
    }

    @Test
    void bulkUpload_withUnknownModule_rejectsV12() {
        when(moduleRepository.findBySpecializationIdAndNameIgnoreCase(any(), anyString()))
            .thenReturn(Optional.empty());
        doReturn(parsed(validRow())).when(csvParserService).parse(any(), any());

        assertSingleError(service.bulkUpload(file()), "MODULE_NAME", "V12");
    }

    @Test
    void bulkUpload_withUnknownLab_rejectsV13() {
        when(labRepository.findByModuleIdAndTitleIgnoreCase(any(), anyString()))
            .thenReturn(Optional.empty());
        doReturn(parsed(validRow())).when(csvParserService).parse(any(), any());

        assertSingleError(service.bulkUpload(file()), "LAB_TITLE", "V13");
    }

    @Test
    void bulkUpload_withMaxScoreMismatch_rejectsV14() {
        LabResultCsvRow row = validRow();
        row.setScore("18");
        row.setMaxScore("50");
        doReturn(parsed(row)).when(csvParserService).parse(any(), any());

        assertSingleError(service.bulkUpload(file()), "MAX_SCORE", "V14");
    }

    @Test
    void bulkUpload_whenInstructorNotAssigned_rejectsV15() {
        when(userModuleAssignmentRepository.existsByUserIdAndModuleId(any(), any())).thenReturn(false);
        doReturn(parsed(validRow())).when(csvParserService).parse(any(), any());

        assertSingleError(service.bulkUpload(file()), "MODULE_NAME", "V15");
    }

    @Test
    void bulkUpload_whenAdmin_bypassesV15AndInserts() {
        setActor(admin);
        when(userModuleAssignmentRepository.existsByUserIdAndModuleId(any(), any())).thenReturn(false);
        doReturn(parsed(validRow())).when(csvParserService).parse(any(), any());

        LabResultUploadResponse result = service.bulkUpload(file());

        assertThat(result.getInsertedCount()).isEqualTo(1);
        assertThat(result.getRejectedCount()).isZero();
        verify(userModuleAssignmentRepository, never()).existsByUserIdAndModuleId(any(), any());
    }

    // ── Reconcile against DB (V17): insert / update / skip ──────────────────────

    @Test
    void bulkUpload_whenResultExistsWithDifferentScore_updatesInPlace() {
        LabResult existing = existingResult(new BigDecimal("10.00"));
        when(labResultRepository.findByLearnerIdAndLabIdAndAttemptNumber(any(), any(), anyShort()))
            .thenReturn(Optional.of(existing));
        doReturn(parsed(validRow())).when(csvParserService).parse(any(), any());

        LabResultUploadResponse result = service.bulkUpload(file());

        assertThat(result.getUpdatedCount()).isEqualTo(1);
        assertThat(result.getInsertedCount()).isZero();
        assertThat(result.getRejectedCount()).isZero();
        assertThat(existing.getScore()).isEqualByComparingTo("18");
        assertThat(existing.getUpdatedBy()).isEqualTo(instructor.getId());
    }

    @Test
    void bulkUpload_whenResultExistsIdentical_skips() {
        LabResult existing = existingResult(new BigDecimal("18.00"));
        existing.setGradedBy("Grader");
        when(labResultRepository.findByLearnerIdAndLabIdAndAttemptNumber(any(), any(), anyShort()))
            .thenReturn(Optional.of(existing));
        doReturn(parsed(validRow())).when(csvParserService).parse(any(), any());

        LabResultUploadResponse result = service.bulkUpload(file());

        assertThat(result.getSkippedCount()).isEqualTo(1);
        assertThat(result.getInsertedCount()).isZero();
        assertThat(result.getUpdatedCount()).isZero();
    }

    @Test
    void bulkUpload_reUploadReconcilesInsertUpdateAndSkip() {
        Lab lab2 = Lab.builder().id(UUID.randomUUID()).title("Lab 2")
            .maxScore(new BigDecimal("20.00")).module(module).build();
        when(labRepository.findByModuleIdAndTitleIgnoreCase(any(), eq("Lab 1")))
            .thenReturn(Optional.of(lab));
        when(labRepository.findByModuleIdAndTitleIgnoreCase(any(), eq("Lab 2")))
            .thenReturn(Optional.of(lab2));

        // Lab 1 / attempt 1 -> not in DB -> insert (a previously-fixed or new row)
        when(labResultRepository.findByLearnerIdAndLabIdAndAttemptNumber(
            any(), eq(lab.getId()), eq((short) 1))).thenReturn(Optional.empty());
        // Lab 1 / attempt 2 -> identical in DB -> skip
        LabResult identical = existingResult(new BigDecimal("18.00"));
        identical.setGradedBy("Grader");
        identical.setAttemptNumber((short) 2);
        when(labResultRepository.findByLearnerIdAndLabIdAndAttemptNumber(
            any(), eq(lab.getId()), eq((short) 2))).thenReturn(Optional.of(identical));
        // Lab 2 / attempt 1 -> different score in DB -> update
        LabResult changed = existingResult(new BigDecimal("5.00"));
        changed.setLab(lab2);
        when(labResultRepository.findByLearnerIdAndLabIdAndAttemptNumber(
            any(), eq(lab2.getId()), eq((short) 1))).thenReturn(Optional.of(changed));

        LabResultCsvRow insertRow = row("jane@test.com", "Cohort 1", "Data Analytics",
            "Module 1", "Lab 1", "18", "20", "1", "2026-05-30", "Grader");
        LabResultCsvRow skipRow = row("jane@test.com", "Cohort 1", "Data Analytics",
            "Module 1", "Lab 1", "18", "20", "2", "2026-05-30", "Grader");
        LabResultCsvRow updateRow = row("jane@test.com", "Cohort 1", "Data Analytics",
            "Module 1", "Lab 2", "18", "20", "1", "2026-05-30", "Grader");
        doReturn(parsed(insertRow, skipRow, updateRow))
            .when(csvParserService).parse(any(), any());

        LabResultUploadResponse result = service.bulkUpload(file());

        assertThat(result.getInsertedCount()).isEqualTo(1);
        assertThat(result.getUpdatedCount()).isEqualTo(1);
        assertThat(result.getSkippedCount()).isEqualTo(1);
        assertThat(result.getRejectedCount()).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private LabResult existingResult(BigDecimal score) {
        return LabResult.builder()
            .id(UUID.randomUUID())
            .learner(learner)
            .lab(lab)
            .score(score)
            .maxScoreSnapshot(new BigDecimal("20.00"))
            .attemptNumber((short) 1)
            .submittedOn(LocalDate.parse("2026-05-30"))
            .gradedBy("Grader")
            .build();
    }

    private LabResultCsvRow validRow() {
        return row("jane@test.com", "Cohort 1", "Data Analytics", "Module 1", "Lab 1",
            "18", "20", "1", "2026-05-30", "Grader");
    }

    private LabResultCsvRow row(String email, String cohortName, String spec, String moduleName,
            String labTitle, String score, String maxScore, String attempt,
            String submittedOn, String gradedBy) {
        LabResultCsvRow r = new LabResultCsvRow();
        r.setLearnerEmail(email);
        r.setCohortName(cohortName);
        r.setSpecializationName(spec);
        r.setModuleName(moduleName);
        r.setLabTitle(labTitle);
        r.setScore(score);
        r.setMaxScore(maxScore);
        r.setAttemptNumber(attempt);
        r.setSubmittedOn(submittedOn);
        r.setGradedBy(gradedBy);
        return r;
    }

    private CsvParseResult<LabResultCsvRow> parsed(LabResultCsvRow... rows) {
        List<ParsedRow<LabResultCsvRow>> list = new ArrayList<>();
        long line = 2;
        for (LabResultCsvRow r : rows) {
            list.add(new ParsedRow<>(line++, r));
        }
        return new CsvParseResult<>(list, List.of());
    }

    private MultipartFile file() {
        return new MockMultipartFile("file", "lab_results.csv", "text/csv", "content".getBytes());
    }

    private void setActor(User user) {
        SecurityContextImpl ctx = new SecurityContextImpl();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
            user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(ctx);
    }

    private void assertSingleError(LabResultUploadResponse result, String field, String rule) {
        assertThat(result.getInsertedCount()).isZero();
        assertThat(result.getRejectedCount()).isEqualTo(1);
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).field()).isEqualTo(field);
        assertThat(result.getErrors().get(0).rule()).isEqualTo(rule);
    }

    private void assertHasError(LabResultUploadResponse result, String field, String rule) {
        assertThat(result.getInsertedCount()).isZero();
        assertThat(result.getRejectedCount()).isEqualTo(1);
        assertThat(result.getErrors())
            .anyMatch(e -> field.equals(e.field()) && rule.equals(e.rule()));
    }
}
