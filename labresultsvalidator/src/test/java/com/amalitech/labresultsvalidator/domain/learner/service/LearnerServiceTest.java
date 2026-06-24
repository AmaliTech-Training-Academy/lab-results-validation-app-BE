package com.amalitech.labresultsvalidator.domain.learner.service;

import com.amalitech.labresultsvalidator.common.csv.CsvParseResult;
import com.amalitech.labresultsvalidator.common.csv.CsvParserService;
import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import com.amalitech.labresultsvalidator.common.csv.CsvWriterService;
import com.amalitech.labresultsvalidator.common.csv.MalformedCsvException;
import com.amalitech.labresultsvalidator.common.csv.ParsedRow;
import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.enums.LearnerStatus;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.lab_result.repository.LabResultRepository;
import com.amalitech.labresultsvalidator.domain.learner.dto.BulkUploadResponse;
import com.amalitech.labresultsvalidator.domain.learner.dto.CreateLearnerRequest;
import com.amalitech.labresultsvalidator.domain.learner.dto.LearnerCsvRow;
import com.amalitech.labresultsvalidator.domain.learner.dto.LearnerResponse;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.learner.dto.UpdateLearnerRequest;
import com.amalitech.labresultsvalidator.domain.learner.dto.UpdateLearnerStatusRequest;
import com.amalitech.labresultsvalidator.domain.learner.entity.Learner;
import com.amalitech.labresultsvalidator.domain.learner.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.specialization.repository.SpecializationRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearnerServiceTest {

    @Mock private LearnerRepository learnerRepository;
    @Mock private CohortRepository cohortRepository;
    @Mock private SpecializationRepository specializationRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private CsvParserService csvParserService;
    @Mock private CsvWriterService csvWriterService;

    @InjectMocks
    private LearnerService learnerService;

    private User currentUser;
    private Cohort cohort;
    private Specialization specialization;
    private CreateLearnerRequest createRequest;

    @BeforeEach
    void setUp() {
        currentUser = User.builder()
                .id(UUID.randomUUID())
                .email("admin@test.com")
                .passwordHash("hash")
                .role(UserRole.ADMIN)
                .build();

        cohort = Cohort.builder()
                .id(UUID.randomUUID())
                .name("Cohort 1 — Spring 2026")
                .active(true)
                .locked(false)
                .build();

        specialization = Specialization.builder()
                .id(UUID.randomUUID())
                .name("Data Analytics")
                .cohort(cohort)
                .build();

        createRequest = new CreateLearnerRequest();
        setField(createRequest, "fullName", "Ama Owusu");
        setField(createRequest, "email", "ama.owusu@learner.labgate.com");
        setField(createRequest, "cohortId", cohort.getId());
        setField(createRequest, "specializationId", specialization.getId());

        SecurityContextImpl ctx = new SecurityContextImpl();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                currentUser, null, currentUser.getAuthorities()));
        SecurityContextHolder.setContext(ctx);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── AC-1: Single learner creation ─────────────────────────────────────────

    @Test
    void createLearner_withValidData_returnsPopulatedResponse() {
        Learner saved = buildLearner("ama.owusu@learner.labgate.com");
        when(learnerRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.of(cohort));
        when(specializationRepository.findByIdAndCohortId(any(), any()))
                .thenReturn(Optional.of(specialization));
        when(learnerRepository.save(any())).thenReturn(saved);

        LearnerResponse response = learnerService.createLearner(createRequest);

        assertThat(response.getEmail()).isEqualTo("ama.owusu@learner.labgate.com");
        assertThat(response.getFullName()).isEqualTo("Ama Owusu");
        assertThat(response.getStatus()).isEqualTo(LearnerStatus.ACTIVE);
        assertThat(response.getId()).isNotNull();
    }

    @Test
    void createLearner_setsStatusActiveByDefault() {
        when(learnerRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(cohortRepository.findById(any())).thenReturn(Optional.of(cohort));
        when(specializationRepository.findByIdAndCohortId(any(), any()))
                .thenReturn(Optional.of(specialization));
        when(learnerRepository.save(any())).thenReturn(buildLearner("ama@test.com"));

        learnerService.createLearner(createRequest);

        ArgumentCaptor<Learner> captor = ArgumentCaptor.forClass(Learner.class);
        verify(learnerRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(LearnerStatus.ACTIVE);
    }

    @Test
    void createLearner_setsAuditFieldsFromCurrentUser() {
        when(learnerRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(cohortRepository.findById(any())).thenReturn(Optional.of(cohort));
        when(specializationRepository.findByIdAndCohortId(any(), any()))
                .thenReturn(Optional.of(specialization));
        when(learnerRepository.save(any())).thenReturn(buildLearner("ama@test.com"));

        learnerService.createLearner(createRequest);

        ArgumentCaptor<Learner> captor = ArgumentCaptor.forClass(Learner.class);
        verify(learnerRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(currentUser.getId());
        assertThat(captor.getValue().getUpdatedBy()).isEqualTo(currentUser.getId());
    }

    @Test
    void createLearner_withDuplicateEmail_throwsDuplicateResourceException() {
        when(learnerRepository.existsByEmailIgnoreCase(anyString())).thenReturn(true);

        assertThatThrownBy(() -> learnerService.createLearner(createRequest))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already exists");

        verify(learnerRepository, never()).save(any());
    }

    @Test
    void createLearner_withUnknownCohort_throwsResourceNotFoundException() {
        when(learnerRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(cohortRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> learnerService.createLearner(createRequest))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Cohort");

        verify(learnerRepository, never()).save(any());
    }

    @Test
    void createLearner_withSpecializationNotInCohort_throwsResourceNotFoundException() {
        when(learnerRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(cohortRepository.findById(any())).thenReturn(Optional.of(cohort));
        when(specializationRepository.findByIdAndCohortId(any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> learnerService.createLearner(createRequest))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Specialization");

        verify(learnerRepository, never()).save(any());
    }

    // ── AC-2: Bulk CSV upload ─────────────────────────────────────────────────

    @Test
    void bulkUpload_withMalformedCsv_throwsMalformedCsvException() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "learners.csv", "text/csv", "bad content".getBytes());
        when(csvParserService.parse(any(), any()))
                .thenThrow(new MalformedCsvException("CSV is missing required column(s)"));

        assertThatThrownBy(() -> learnerService.bulkUpload(file))
                .isInstanceOf(MalformedCsvException.class);

        verify(learnerRepository, never()).saveAll(any());
    }

    @Test
    void bulkUpload_withAllValidRows_commitsAllAndReturnsZeroErrors() {
        LearnerCsvRow row = csvRow("Ama Owusu", "ama@test.com", "Cohort 1", "Data Analytics");
        CsvParseResult<LearnerCsvRow> parsed = new CsvParseResult<>(
                List.of(new ParsedRow<>(2L, row)), List.of());

        doReturn(parsed).when(csvParserService).parse(any(), any());
        when(learnerRepository.findExistingEmails(any())).thenReturn(Set.of());
        when(cohortRepository.findByNameIgnoreCase("Cohort 1")).thenReturn(Optional.of(cohort));
        when(specializationRepository.findByCohortIdAndNameIgnoreCase(any(), anyString()))
                .thenReturn(Optional.of(specialization));
        when(learnerRepository.save(any())).thenReturn(buildLearner("ama@test.com"));

        BulkUploadResponse result = learnerService.bulkUpload(
                new MockMultipartFile("file", "learners.csv", "text/csv", "dummy".getBytes()));

        assertThat(result.getAcceptedCount()).isEqualTo(1);
        assertThat(result.getRejectedCount()).isZero();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void bulkUpload_withPartialSuccess_commitsOnlyValidRows() {
        LearnerCsvRow valid = csvRow("Ama Owusu", "ama@test.com", "Cohort 1", "Data Analytics");
        CsvRowError preError = new CsvRowError(3L, "EMAIL", "Email is required");
        CsvParseResult<LearnerCsvRow> parsed = new CsvParseResult<>(
                List.of(new ParsedRow<>(2L, valid)), List.of(preError));

        doReturn(parsed).when(csvParserService).parse(any(), any());
        when(learnerRepository.findExistingEmails(any())).thenReturn(Set.of());
        when(cohortRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.of(cohort));
        when(specializationRepository.findByCohortIdAndNameIgnoreCase(any(), anyString()))
                .thenReturn(Optional.of(specialization));
        when(learnerRepository.save(any())).thenReturn(buildLearner("ama@test.com"));

        BulkUploadResponse result = learnerService.bulkUpload(
                new MockMultipartFile("file", "f.csv", "text/csv", "dummy".getBytes()));

        assertThat(result.getAcceptedCount()).isEqualTo(1);
        assertThat(result.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void bulkUpload_withInFileDuplicateEmails_rejectsBothRows() {
        LearnerCsvRow row1 = csvRow("Ama Owusu", "ama@test.com", "Cohort 1", "Data Analytics");
        LearnerCsvRow row2 = csvRow("Ama Owusu 2", "ama@test.com", "Cohort 1", "Data Analytics");
        CsvParseResult<LearnerCsvRow> parsed = new CsvParseResult<>(
                List.of(new ParsedRow<>(2L, row1), new ParsedRow<>(3L, row2)), List.of());

        doReturn(parsed).when(csvParserService).parse(any(), any());
        when(learnerRepository.findExistingEmails(any())).thenReturn(Set.of());

        BulkUploadResponse result = learnerService.bulkUpload(
                new MockMultipartFile("file", "f.csv", "text/csv", "dummy".getBytes()));

        assertThat(result.getAcceptedCount()).isZero();
        assertThat(result.getRejectedCount()).isEqualTo(2);
        assertThat(result.getErrors())
                .extracting(CsvRowError::message)
                .allMatch(m -> m.contains("Duplicate email"));
    }

    @Test
    void bulkUpload_withMalformedEmailRow_rejectsThatRow() {
        LearnerCsvRow row = csvRow("Ama Owusu", "not-an-email", "Cohort 1", "Data Analytics");
        CsvParseResult<LearnerCsvRow> parsed = new CsvParseResult<>(
                List.of(new ParsedRow<>(2L, row)), List.of());

        doReturn(parsed).when(csvParserService).parse(any(), any());
        when(learnerRepository.findExistingEmails(any())).thenReturn(Set.of());

        BulkUploadResponse result = learnerService.bulkUpload(
                new MockMultipartFile("file", "f.csv", "text/csv", "dummy".getBytes()));

        assertThat(result.getAcceptedCount()).isZero();
        assertThat(result.getRejectedCount()).isEqualTo(1);
        assertThat(result.getErrors().get(0).field()).isEqualTo("EMAIL");
    }

    @Test
    void bulkUpload_withEmailAlreadyInDb_rejectsThatRow() {
        LearnerCsvRow row = csvRow("Ama Owusu", "ama@test.com", "Cohort 1", "Data Analytics");
        CsvParseResult<LearnerCsvRow> parsed = new CsvParseResult<>(
                List.of(new ParsedRow<>(2L, row)), List.of());

        doReturn(parsed).when(csvParserService).parse(any(), any());
        when(learnerRepository.findExistingEmails(any())).thenReturn(Set.of("ama@test.com"));

        BulkUploadResponse result = learnerService.bulkUpload(
                new MockMultipartFile("file", "f.csv", "text/csv", "dummy".getBytes()));

        assertThat(result.getAcceptedCount()).isZero();
        assertThat(result.getErrors().get(0).field()).isEqualTo("EMAIL");
    }

    @Test
    void bulkUpload_withUnknownCohortName_rejectsThatRow() {
        LearnerCsvRow row = csvRow("Ama Owusu", "ama@test.com", "Unknown Cohort", "Data Analytics");
        CsvParseResult<LearnerCsvRow> parsed = new CsvParseResult<>(
                List.of(new ParsedRow<>(2L, row)), List.of());

        doReturn(parsed).when(csvParserService).parse(any(), any());
        when(learnerRepository.findExistingEmails(any())).thenReturn(Set.of());
        when(cohortRepository.findByNameIgnoreCase("Unknown Cohort")).thenReturn(Optional.empty());

        BulkUploadResponse result = learnerService.bulkUpload(
                new MockMultipartFile("file", "f.csv", "text/csv", "dummy".getBytes()));

        assertThat(result.getAcceptedCount()).isZero();
        assertThat(result.getErrors().get(0).field()).isEqualTo("COHORT_NAME");
    }

    @Test
    void bulkUpload_withAmbiguousCohortName_rejectsThatRowWithoutAborting() {
        LearnerCsvRow row = csvRow("Ama Owusu", "ama@test.com", "cohort 1", "Data Analytics");
        CsvParseResult<LearnerCsvRow> parsed = new CsvParseResult<>(
                List.of(new ParsedRow<>(2L, row)), List.of());

        doReturn(parsed).when(csvParserService).parse(any(), any());
        when(learnerRepository.findExistingEmails(any())).thenReturn(Set.of());
        when(cohortRepository.findByNameIgnoreCase("cohort 1"))
                .thenThrow(new IncorrectResultSizeDataAccessException(1, 2));

        BulkUploadResponse result = learnerService.bulkUpload(
                new MockMultipartFile("file", "f.csv", "text/csv", "dummy".getBytes()));

        assertThat(result.getAcceptedCount()).isZero();
        assertThat(result.getRejectedCount()).isEqualTo(1);
        assertThat(result.getErrors().get(0).field()).isEqualTo("COHORT_NAME");
        assertThat(result.getErrors().get(0).message()).contains("ambiguous");
        verify(learnerRepository, never()).save(any());
    }

    @Test
    void bulkUpload_withDbErrorOnOneRow_rejectsThatRowAndImportsOthers() {
        LearnerCsvRow good = csvRow("Ama Owusu", "ama@test.com", "Cohort 1", "Data Analytics");
        LearnerCsvRow bad = csvRow("Kofi Mensah", "kofi@test.com", "Cohort 1", "Data Analytics");
        CsvParseResult<LearnerCsvRow> parsed = new CsvParseResult<>(
                List.of(new ParsedRow<>(2L, good), new ParsedRow<>(3L, bad)), List.of());

        doReturn(parsed).when(csvParserService).parse(any(), any());
        when(learnerRepository.findExistingEmails(any())).thenReturn(Set.of());
        when(cohortRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.of(cohort));
        when(specializationRepository.findByCohortIdAndNameIgnoreCase(any(), anyString()))
                .thenReturn(Optional.of(specialization));
        when(learnerRepository.save(any()))
                .thenReturn(buildLearner("ama@test.com"))
                .thenThrow(new DataAccessResourceFailureException("connection reset by peer"));

        BulkUploadResponse result = learnerService.bulkUpload(
                new MockMultipartFile("file", "f.csv", "text/csv", "dummy".getBytes()));

        assertThat(result.getAcceptedCount()).isEqualTo(1);
        assertThat(result.getRejectedCount()).isEqualTo(1);
        assertThat(result.getErrors().get(0).rowNumber()).isEqualTo(3L);
        assertThat(result.getErrors().get(0).message())
                .contains("Failed to save to the database")
                .contains("connection reset by peer");
    }

    @Test
    void bulkUpload_withUnexpectedErrorOnRow_rejectsThatRowAndImportsOthers() {
        LearnerCsvRow good = csvRow("Ama Owusu", "ama@test.com", "Cohort 1", "Data Analytics");
        LearnerCsvRow bad = csvRow("Kofi Mensah", "kofi@test.com", "Cohort 1", "Data Analytics");
        CsvParseResult<LearnerCsvRow> parsed = new CsvParseResult<>(
                List.of(new ParsedRow<>(2L, good), new ParsedRow<>(3L, bad)), List.of());

        doReturn(parsed).when(csvParserService).parse(any(), any());
        when(learnerRepository.findExistingEmails(any())).thenReturn(Set.of());
        when(cohortRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.of(cohort));
        when(specializationRepository.findByCohortIdAndNameIgnoreCase(any(), anyString()))
                .thenReturn(Optional.of(specialization));
        when(learnerRepository.save(any()))
                .thenReturn(buildLearner("ama@test.com"))
                .thenThrow(new IllegalStateException("boom"));

        BulkUploadResponse result = learnerService.bulkUpload(
                new MockMultipartFile("file", "f.csv", "text/csv", "dummy".getBytes()));

        assertThat(result.getAcceptedCount()).isEqualTo(1);
        assertThat(result.getRejectedCount()).isEqualTo(1);
        assertThat(result.getErrors().get(0).message()).contains("Failed to process row");
    }

    // ── AC-3: Roster management ───────────────────────────────────────────────

    @Test
    void getLearnerById_whenNotFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(learnerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> learnerService.getLearnerById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void updateLearnerStatus_archivesLearner() {
        Learner learner = buildLearner("ama@test.com");
        when(learnerRepository.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(learnerRepository.save(any())).thenReturn(learner);

        UpdateLearnerStatusRequest req = new UpdateLearnerStatusRequest();
        setField(req, "status", LearnerStatus.ARCHIVED);

        LearnerResponse response = learnerService.updateLearnerStatus(learner.getId(), req);

        ArgumentCaptor<Learner> captor = ArgumentCaptor.forClass(Learner.class);
        verify(learnerRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(LearnerStatus.ARCHIVED);
    }

    @Test
    void updateLearnerStatus_reactivatesArchivedLearner() {
        Learner learner = buildLearner("ama@test.com");
        learner.setStatus(LearnerStatus.ARCHIVED);
        when(learnerRepository.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(learnerRepository.save(any())).thenReturn(learner);

        UpdateLearnerStatusRequest req = new UpdateLearnerStatusRequest();
        setField(req, "status", LearnerStatus.ACTIVE);

        learnerService.updateLearnerStatus(learner.getId(), req);

        ArgumentCaptor<Learner> captor = ArgumentCaptor.forClass(Learner.class);
        verify(learnerRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(LearnerStatus.ACTIVE);
    }

    @Test
    void deleteLearner_whenLabResultsExist_throwsDuplicateResourceException() {
        Learner learner = buildLearner("ama@test.com");
        when(learnerRepository.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(labResultRepository.existsByLearnerId(learner.getId())).thenReturn(true);

        assertThatThrownBy(() -> learnerService.deleteLearner(learner.getId()))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Archive the learner instead");

        verify(learnerRepository, never()).delete(any(Learner.class));
    }

    @Test
    void deleteLearner_whenNoResults_deletesSuccessfully() {
        Learner learner = buildLearner("ama@test.com");
        when(learnerRepository.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(labResultRepository.existsByLearnerId(learner.getId())).thenReturn(false);

        learnerService.deleteLearner(learner.getId());

        verify(learnerRepository).delete(any(Learner.class));
    }

    @Test
    void getLearnerById_whenFound_returnsCorrectResponse() {
        Learner learner = buildLearner("ama@test.com");
        when(learnerRepository.findById(learner.getId())).thenReturn(Optional.of(learner));

        LearnerResponse response = learnerService.getLearnerById(learner.getId());

        assertThat(response.getId()).isEqualTo(learner.getId());
        assertThat(response.getEmail()).isEqualTo("ama@test.com");
        assertThat(response.getCohortName()).isEqualTo(cohort.getName());
        assertThat(response.getSpecializationName()).isEqualTo(specialization.getName());
    }

    @Test
    void getLearners_returnsPagedResponseWithMappedContent() {
        Learner learner = buildLearner("ama@test.com");
        org.springframework.data.domain.PageImpl<Learner> page =
            new org.springframework.data.domain.PageImpl<>(List.of(learner));
        when(learnerRepository.findAll(
                any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(page);

        PagedResponse<LearnerResponse> result = learnerService.getLearners(
            null, null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getEmail()).isEqualTo("ama@test.com");
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void updateLearner_withValidData_updatesAndReturnsResponse() {
        Learner learner = buildLearner("ama@test.com");
        when(learnerRepository.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.of(cohort));
        when(specializationRepository.findByIdAndCohortId(any(), any()))
            .thenReturn(Optional.of(specialization));
        when(learnerRepository.save(any())).thenReturn(learner);

        UpdateLearnerRequest req = new UpdateLearnerRequest();
        setField(req, "fullName", "Ama Owusu-Mensah");
        setField(req, "cohortId", cohort.getId());
        setField(req, "specializationId", specialization.getId());

        learnerService.updateLearner(learner.getId(), req);

        ArgumentCaptor<Learner> captor = ArgumentCaptor.forClass(Learner.class);
        verify(learnerRepository).save(captor.capture());
        assertThat(captor.getValue().getFullName()).isEqualTo("Ama Owusu-Mensah");
    }

    @Test
    void updateLearner_withUnknownCohort_throwsResourceNotFoundException() {
        Learner learner = buildLearner("ama@test.com");
        when(learnerRepository.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(cohortRepository.findById(any())).thenReturn(Optional.empty());

        UpdateLearnerRequest req = new UpdateLearnerRequest();
        setField(req, "fullName", "Name");
        setField(req, "cohortId", UUID.randomUUID());
        setField(req, "specializationId", UUID.randomUUID());

        assertThatThrownBy(() -> learnerService.updateLearner(learner.getId(), req))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(learnerRepository, never()).save(any());
    }

    @Test
    void updateLearner_withSpecNotInCohort_throwsResourceNotFoundException() {
        Learner learner = buildLearner("ama@test.com");
        when(learnerRepository.findById(learner.getId())).thenReturn(Optional.of(learner));
        when(cohortRepository.findById(any())).thenReturn(Optional.of(cohort));
        when(specializationRepository.findByIdAndCohortId(any(), any()))
            .thenReturn(Optional.empty());

        UpdateLearnerRequest req = new UpdateLearnerRequest();
        setField(req, "fullName", "Name");
        setField(req, "cohortId", cohort.getId());
        setField(req, "specializationId", UUID.randomUUID());

        assertThatThrownBy(() -> learnerService.updateLearner(learner.getId(), req))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteLearner_whenNotFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(learnerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> learnerService.deleteLearner(id))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(id.toString());

        verify(learnerRepository, never()).delete(any(Learner.class));
    }

    @Test
    void bulkUpload_withSpecializationNotInCohort_rejectsThatRow() {
        LearnerCsvRow row = csvRow("Ama Owusu", "ama@test.com", "Cohort 1", "Wrong Track");
        CsvParseResult<LearnerCsvRow> parsed = new CsvParseResult<>(
                List.of(new ParsedRow<>(2L, row)), List.of());

        doReturn(parsed).when(csvParserService).parse(any(), any());
        when(learnerRepository.findExistingEmails(any())).thenReturn(Set.of());
        when(cohortRepository.findByNameIgnoreCase("Cohort 1")).thenReturn(Optional.of(cohort));
        when(specializationRepository.findByCohortIdAndNameIgnoreCase(any(), anyString()))
            .thenReturn(Optional.empty());

        BulkUploadResponse result = learnerService.bulkUpload(
            new MockMultipartFile("file", "f.csv", "text/csv", "dummy".getBytes()));

        assertThat(result.getAcceptedCount()).isZero();
        assertThat(result.getErrors().get(0).field()).isEqualTo("SPECIALIZATION_NAME");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Learner buildLearner(String email) {
        Learner learner = Learner.builder()
                .id(UUID.randomUUID())
                .fullName("Ama Owusu")
                .email(email)
                .cohort(cohort)
                .specialization(specialization)
                .status(LearnerStatus.ACTIVE)
                .build();
        learner.setCreatedBy(currentUser.getId());
        learner.setUpdatedBy(currentUser.getId());
        return learner;
    }

    private LearnerCsvRow csvRow(String fullName, String email,
            String cohortName, String specializationName) {
        LearnerCsvRow row = new LearnerCsvRow();
        row.setFullName(fullName);
        row.setEmail(email);
        row.setCohortName(cohortName);
        row.setSpecializationName(specializationName);
        return row;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
