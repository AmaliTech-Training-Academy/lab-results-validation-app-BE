package com.amalitech.labresultsvalidator.domain.lab_result.service;

import com.amalitech.labresultsvalidator.common.csv.CsvParseResult;
import com.amalitech.labresultsvalidator.common.csv.CsvParserService;
import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import com.amalitech.labresultsvalidator.common.csv.CsvWriterService;
import com.amalitech.labresultsvalidator.common.csv.MalformedCsvException;
import com.amalitech.labresultsvalidator.common.csv.ParsedRow;
import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultResponse;
import com.amalitech.labresultsvalidator.domain.csvUploads.entity.CsvUpload;
import com.amalitech.labresultsvalidator.domain.csvUploads.repository.CsvUploadRepository;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.enums.LearnerStatus;
import com.amalitech.labresultsvalidator.domain.enums.UploadStatus;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.lab.entity.Lab;
import com.amalitech.labresultsvalidator.domain.lab.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultCorrectionRow;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

        cohort = Cohort.builder().id(UUID.randomUUID()).name("Cohort 1").active(true).locked(true).build();
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

        verify(labResultRepository, never()).save(any());
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
        verify(csvUploadRepository, times(2)).save(captor.capture());
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

    // ── Corrections-only CSV (rejected rows + ERROR_MESSAGE) ────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void bulkUpload_withRejectedRow_persistsRejectedRowsInReport() {
        LabResultCsvRow row = validRow();
        row.setLearnerEmail("not-an-email");
        doReturn(parsed(row)).when(csvParserService).parse(any(), any());

        service.bulkUpload(file());

        ArgumentCaptor<CsvUpload> captor = ArgumentCaptor.forClass(CsvUpload.class);
        verify(csvUploadRepository, times(2)).save(captor.capture());
        Map<String, Object> report = captor.getValue().getErrorReportJson();

        List<Map<String, Object>> rejectedRows =
            (List<Map<String, Object>>) report.get("rejectedRows");
        assertThat(rejectedRows).hasSize(1);
        Map<String, Object> rejected = rejectedRows.get(0);
        assertThat(rejected.get("LEARNER_EMAIL")).isEqualTo("not-an-email");
        assertThat(rejected.get("COHORT_NAME")).isEqualTo("Cohort 1");
        assertThat(rejected.get("SCORE")).isEqualTo("18");
        assertThat((String) rejected.get("ERROR_MESSAGE")).contains("LEARNER_EMAIL");
    }

    @Test
    void downloadCorrections_withUnknownId_throwsResourceNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(csvUploadRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downloadCorrections(unknownId, mock(HttpServletResponse.class)))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(unknownId.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadCorrections_streamsPersistedRejectedRows() throws Exception {
        UUID uploadId = UUID.randomUUID();
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("LEARNER_EMAIL", "not-an-email");
        rejected.put("COHORT_NAME", "Cohort 1");
        rejected.put("SCORE", "18");
        rejected.put("ERROR_MESSAGE", "LEARNER_EMAIL: 'not-an-email' is not a valid email address");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("rejectedRows", List.of(rejected));
        CsvUpload upload = CsvUpload.builder().id(uploadId).errorReportJson(report).build();
        when(csvUploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        service.downloadCorrections(uploadId, response);

        verify(response).setContentType("text/csv");
        verify(response).setHeader(eq("Content-Disposition"),
            eq("attachment; filename=\"corrections_" + uploadId + ".csv\""));
        ArgumentCaptor<List<LabResultCorrectionRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(csvWriterService).write(any(), captor.capture(), eq(LabResultCorrectionRow.class));
        assertThat(captor.getValue()).hasSize(1);
        LabResultCorrectionRow written = captor.getValue().get(0);
        assertThat(written.getLearnerEmail()).isEqualTo("not-an-email");
        assertThat(written.getCohortName()).isEqualTo("Cohort 1");
        assertThat(written.getScore()).isEqualTo("18");
        assertThat(written.getErrorMessage())
            .isEqualTo("LEARNER_EMAIL: 'not-an-email' is not a valid email address");
    }

    @Test
    void downloadCorrections_withNoRejectedRows_streamsHeaderOnly() throws Exception {
        UUID uploadId = UUID.randomUUID();
        CsvUpload upload = CsvUpload.builder().id(uploadId).build();
        when(csvUploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        service.downloadCorrections(uploadId, response);

        verify(csvWriterService).writeTemplate(any(), eq(LabResultCorrectionRow.class));
        verify(csvWriterService, never()).write(any(), any(), eq(LabResultCorrectionRow.class));
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

    // ── Cohort-lock gate (V18) ────────────────────────────────────────────────

    @Test
    void bulkUpload_whenCohortNotLocked_instructorIsRejectedV18() {
        Cohort unlocked = Cohort.builder()
            .id(UUID.randomUUID()).name("Cohort 1").active(true).locked(false).build();
        Learner unlockedLearner = Learner.builder()
            .id(UUID.randomUUID()).fullName("Jane Doe").email("jane@test.com")
            .cohort(unlocked).specialization(specialization).status(LearnerStatus.ACTIVE).build();
        when(learnerRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(unlockedLearner));
        doReturn(parsed(validRow())).when(csvParserService).parse(any(), any());

        assertSingleError(service.bulkUpload(file()), "COHORT_NAME", "V18");
    }

    @Test
    void bulkUpload_whenCohortNotLocked_adminBypasses() {
        setActor(admin);
        Cohort unlocked = Cohort.builder()
            .id(UUID.randomUUID()).name("Cohort 1").active(true).locked(false).build();
        Learner unlockedLearner = Learner.builder()
            .id(UUID.randomUUID()).fullName("Jane Doe").email("jane@test.com")
            .cohort(unlocked).specialization(specialization).status(LearnerStatus.ACTIVE).build();
        when(learnerRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(unlockedLearner));
        doReturn(parsed(validRow())).when(csvParserService).parse(any(), any());

        LabResultUploadResponse result = service.bulkUpload(file());

        assertThat(result.getInsertedCount()).isEqualTo(1);
        assertThat(result.getRejectedCount()).isZero();
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

    // ── DB error handling ──────────────────────────────────────────────────────

    @Test
    void bulkUpload_withDbConstraintViolationOnSave_rejectsThatRowAndSavesOthers() {
        LabResultCsvRow row1 = validRow();
        LabResultCsvRow row2 = row("other@test.com", "Cohort 1", "Data Analytics",
            "Module 1", "Lab 1", "15", "20", "1", "2026-05-30", "Grader");
        doReturn(parsed(row1, row2)).when(csvParserService).parse(any(), any());

        RuntimeException cause = new RuntimeException(
            "ERROR: duplicate key value violates unique constraint\n"
            + "  Detail: Key (learner_id)=(some-uuid) already exists.");
        when(labResultRepository.save(any()))
            .thenReturn(null)
            .thenThrow(new DataIntegrityViolationException("constraint violation", cause));

        LabResultUploadResponse result = service.bulkUpload(file());

        assertThat(result.getInsertedCount()).isEqualTo(1);
        assertThat(result.getRejectedCount()).isEqualTo(1);
        assertThat(result.getErrors().get(0).rowNumber()).isEqualTo(3L);
    }

    @Test
    void bulkUpload_withDbAccessErrorOnSave_rejectsThatRowAndSavesOthers() {
        LabResultCsvRow row1 = validRow();
        LabResultCsvRow row2 = row("other@test.com", "Cohort 1", "Data Analytics",
            "Module 1", "Lab 1", "15", "20", "1", "2026-05-30", "Grader");
        doReturn(parsed(row1, row2)).when(csvParserService).parse(any(), any());

        when(labResultRepository.save(any()))
            .thenReturn(null)
            .thenThrow(new DataAccessResourceFailureException("connection reset by peer"));

        LabResultUploadResponse result = service.bulkUpload(file());

        assertThat(result.getInsertedCount()).isEqualTo(1);
        assertThat(result.getRejectedCount()).isEqualTo(1);
        assertThat(result.getErrors().get(0).rowNumber()).isEqualTo(3L);
        assertThat(result.getErrors().get(0).message())
            .contains("Failed to save to the database")
            .contains("connection reset by peer");
    }

    @Test
    void bulkUpload_withUnexpectedErrorOnSave_rejectsThatRowAndSavesOthers() {
        LabResultCsvRow row1 = validRow();
        LabResultCsvRow row2 = row("other@test.com", "Cohort 1", "Data Analytics",
            "Module 1", "Lab 1", "15", "20", "1", "2026-05-30", "Grader");
        doReturn(parsed(row1, row2)).when(csvParserService).parse(any(), any());

        when(labResultRepository.save(any()))
            .thenReturn(null)
            .thenThrow(new IllegalStateException("unexpected boom"));

        LabResultUploadResponse result = service.bulkUpload(file());

        assertThat(result.getInsertedCount()).isEqualTo(1);
        assertThat(result.getRejectedCount()).isEqualTo(1);
        assertThat(result.getErrors().get(0).rowNumber()).isEqualTo(3L);
        assertThat(result.getErrors().get(0).message()).contains("Failed to process row");
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

    // ── getLabResultsByModule ────────────────────────────────────────────────

    @Test
    void getLabResultsByModule_withKnownModule_returnsMappedResults() {
        UUID moduleId = module.getId();
        LabResult labResult = LabResult.builder()
            .id(UUID.randomUUID())
            .learner(learner)
            .lab(lab)
            .score(new BigDecimal("18.00"))
            .maxScoreSnapshot(new BigDecimal("20.00"))
            .attemptNumber((short) 1)
            .submittedOn(LocalDate.of(2026, 1, 15))
            .gradedBy("Dr. Smith")
            .build();

        Pageable pageable = PageRequest.of(0, 20);
        when(moduleRepository.existsById(moduleId)).thenReturn(true);
        when(labResultRepository.findAllByModuleId(eq(moduleId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(labResult)));

        PagedResponse<LabResultResponse> response = service.getLabResultsByModule(moduleId, pageable);
        List<LabResultResponse> results = response.getContent();

        assertThat(results).hasSize(1);
        LabResultResponse r = results.get(0);
        assertThat(r.getLearnerEmail()).isEqualTo("jane@test.com");
        assertThat(r.getLearnerName()).isEqualTo("Jane Doe");
        assertThat(r.getLabId()).isEqualTo(lab.getId());
        assertThat(r.getLabTitle()).isEqualTo("Lab 1");
        assertThat(r.getScore()).isEqualByComparingTo("18.00");
        assertThat(r.getAttemptNumber()).isEqualTo((short) 1);
        assertThat(r.getGradedBy()).isEqualTo("Dr. Smith");
    }

    @Test
    void getLabResultsByModule_withNoResults_returnsEmptyList() {
        UUID moduleId = module.getId();
        when(moduleRepository.existsById(moduleId)).thenReturn(true);
        when(labResultRepository.findAllByModuleId(eq(moduleId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(service.getLabResultsByModule(moduleId, PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    void getLabResultsByModule_withUnknownModule_throwsResourceNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        when(moduleRepository.existsById(unknownId)).thenReturn(false);

        assertThatThrownBy(() -> service.getLabResultsByModule(unknownId, PageRequest.of(0, 20)))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(unknownId.toString());
    }

    // ── bulkUpload status (COMPLETED / PARTIAL / FAILED) ─────────────────────────

    @Test
    void bulkUpload_withAllValidRows_hasCompletedStatus() {
        doReturn(parsed(validRow())).when(csvParserService).parse(any(), any());

        assertThat(service.bulkUpload(file()).getStatus()).isEqualTo(UploadStatus.COMPLETED);
    }

    @Test
    void bulkUpload_withSomeRejectedRows_hasPartialStatus() {
        LabResultCsvRow invalid = validRow();
        invalid.setScore("999");
        doReturn(parsed(validRow(), invalid)).when(csvParserService).parse(any(), any());

        assertThat(service.bulkUpload(file()).getStatus()).isEqualTo(UploadStatus.PARTIAL);
    }

    @Test
    void bulkUpload_withAllRejectedRows_hasFailedStatus() {
        LabResultCsvRow row = validRow();
        row.setScore("999");
        doReturn(parsed(row)).when(csvParserService).parse(any(), any());

        assertThat(service.bulkUpload(file()).getStatus()).isEqualTo(UploadStatus.FAILED);
    }

    // ── getUploadReport ──────────────────────────────────────────────────────────

    @Test
    void getUploadReport_withCompletedUpload_returnsCompletedStatus() {
        UUID uploadId = UUID.randomUUID();
        CsvUpload upload = upload(instructor, 8, 7, 0, reportMap(5, 2, 0, 0));
        when(csvUploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        LabResultUploadResponse result = service.getUploadReport(uploadId);

        assertThat(result.getStatus()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(result.getInsertedCount()).isEqualTo(5);
        assertThat(result.getUpdatedCount()).isEqualTo(2);
        assertThat(result.getSkippedCount()).isZero();
        assertThat(result.getRejectedCount()).isZero();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void getUploadReport_withPartialUpload_returnsPartialStatusAndErrors() {
        UUID uploadId = UUID.randomUUID();
        Map<String, Object> errorEntry = new LinkedHashMap<>();
        errorEntry.put("rowNumber", 3);
        errorEntry.put("field", "SCORE");
        errorEntry.put("rule", "V5");
        errorEntry.put("message", "Score 25 must be between 0 and max_score 20");

        CsvUpload upload = upload(instructor, 5, 4, 1, reportMap(4, 0, 0, 1, List.of(errorEntry)));
        when(csvUploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        LabResultUploadResponse result = service.getUploadReport(uploadId);

        assertThat(result.getStatus()).isEqualTo(UploadStatus.PARTIAL);
        assertThat(result.getInsertedCount()).isEqualTo(4);
        assertThat(result.getRejectedCount()).isEqualTo(1);
        assertThat(result.getErrors()).hasSize(1);
        CsvRowError err = result.getErrors().get(0);
        assertThat(err.rowNumber()).isEqualTo(3L);
        assertThat(err.field()).isEqualTo("SCORE");
        assertThat(err.rule()).isEqualTo("V5");
        assertThat(err.message()).isEqualTo("Score 25 must be between 0 and max_score 20");
    }

    @Test
    void getUploadReport_withFailedUpload_returnsFailedStatus() {
        UUID uploadId = UUID.randomUUID();
        CsvUpload upload = upload(instructor, 5, 0, 5, reportMap(0, 0, 0, 5));
        when(csvUploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        LabResultUploadResponse result = service.getUploadReport(uploadId);

        assertThat(result.getStatus()).isEqualTo(UploadStatus.FAILED);
        assertThat(result.getInsertedCount()).isZero();
        assertThat(result.getRejectedCount()).isEqualTo(5);
    }

    @Test
    void getUploadReport_withUnknownId_throwsResourceNotFound() {
        UUID uploadId = UUID.randomUUID();
        when(csvUploadRepository.findById(uploadId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUploadReport(uploadId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(uploadId.toString());
    }

    @Test
    void getUploadReport_whenOwnedByOtherInstructor_throwsResourceNotFound() {
        UUID uploadId = UUID.randomUUID();
        User other = User.builder().id(UUID.randomUUID()).email("other@test.com")
            .passwordHash("h").role(UserRole.INSTRUCTOR).build();
        CsvUpload upload = upload(other, 5, 5, 0, reportMap(5, 0, 0, 0));
        when(csvUploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        assertThatThrownBy(() -> service.getUploadReport(uploadId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(uploadId.toString());
    }

    @Test
    void getUploadReport_withNullErrorReport_returnsEmptyErrors() {
        UUID uploadId = UUID.randomUUID();
        CsvUpload upload = upload(instructor, 0, 0, 0, null);
        when(csvUploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        LabResultUploadResponse result = service.getUploadReport(uploadId);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getStatus()).isEqualTo(UploadStatus.COMPLETED);
    }

    private CsvUpload upload(User owner, int totalRows, int acceptedRows, int rejectedRows,
            Map<String, Object> report) {
        return CsvUpload.builder()
            .id(UUID.randomUUID())
            .uploadedByUser(owner)
            .filename("test.csv")
            .fileSha256(UUID.randomUUID().toString())
            .uploadedAt(java.time.OffsetDateTime.now())
            .totalRows(totalRows)
            .acceptedRows(acceptedRows)
            .rejectedRows(rejectedRows)
            .errorReportJson(report)
            .build();
    }

    private Map<String, Object> reportMap(int inserted, int updated, int skipped, int rejected) {
        return reportMap(inserted, updated, skipped, rejected, List.of());
    }

    private Map<String, Object> reportMap(int inserted, int updated, int skipped, int rejected,
            List<Map<String, Object>> errors) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("inserted", inserted);
        summary.put("updated", updated);
        summary.put("skipped", skipped);
        summary.put("rejected", rejected);
        summary.put("totalRows", inserted + updated + skipped + rejected);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", summary);
        report.put("errors", errors);
        report.put("rejectedRows", List.of());
        return report;
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
