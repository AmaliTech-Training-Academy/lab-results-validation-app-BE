package com.amalitech.labresultsvalidator.domain.learner.controller;

import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import com.amalitech.labresultsvalidator.common.csv.MalformedCsvException;
import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.GlobalExceptionHandler;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.enums.LearnerStatus;
import com.amalitech.labresultsvalidator.domain.learner.dto.BulkUploadResponse;
import com.amalitech.labresultsvalidator.domain.learner.dto.LearnerResponse;
import com.amalitech.labresultsvalidator.domain.learner.service.LearnerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LearnerControllerTest {

    private MockMvc mockMvc;

    @Mock
    private LearnerService learnerService;

    @InjectMocks
    private LearnerController learnerController;

    private static final String BASE_URL = "/api/v1/admin/learners";
    private UUID learnerId;
    private LearnerResponse sampleResponse;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(learnerController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();

        learnerId = UUID.randomUUID();
        sampleResponse = LearnerResponse.builder()
                .id(learnerId)
                .fullName("Ama Owusu")
                .email("ama.owusu@learner.labgate.com")
                .cohortId(UUID.randomUUID())
                .cohortName("Cohort 1 — Spring 2026")
                .specializationId(UUID.randomUUID())
                .specializationName("Data Analytics")
                .status(LearnerStatus.ACTIVE)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    // ── AC-1: POST /api/v1/admin/learners ─────────────────────────────────────

    @Test
    void createLearner_withValidData_returns201() throws Exception {
        when(learnerService.createLearner(any())).thenReturn(sampleResponse);

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Ama Owusu",
                                  "email": "ama.owusu@learner.labgate.com",
                                  "cohortId": "%s",
                                  "specializationId": "%s"
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("ama.owusu@learner.labgate.com"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void createLearner_withDuplicateEmail_returns409() throws Exception {
        when(learnerService.createLearner(any()))
                .thenThrow(new DuplicateResourceException("already exists"));

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Ama Owusu",
                                  "email": "ama.owusu@learner.labgate.com",
                                  "cohortId": "%s",
                                  "specializationId": "%s"
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createLearner_withMissingEmail_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Ama Owusu",
                                  "cohortId": "%s",
                                  "specializationId": "%s"
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createLearner_withMalformedEmail_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Ama Owusu",
                                  "email": "not-an-email",
                                  "cohortId": "%s",
                                  "specializationId": "%s"
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createLearner_withUnknownCohort_returns404() throws Exception {
        when(learnerService.createLearner(any()))
                .thenThrow(new ResourceNotFoundException("Cohort not found"));

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Ama Owusu",
                                  "email": "ama@test.com",
                                  "cohortId": "%s",
                                  "specializationId": "%s"
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── AC-2: POST /api/v1/admin/learners/bulk ────────────────────────────────

    @Test
    void bulkUpload_withValidFile_returns200WithCounts() throws Exception {
        BulkUploadResponse uploadResult = BulkUploadResponse.builder()
                .acceptedCount(5)
                .rejectedCount(1)
                .errors(List.of(new CsvRowError(3L, "EMAIL", "Email already exists")))
                .build();
        when(learnerService.bulkUpload(any())).thenReturn(uploadResult);

        MockMultipartFile file = new MockMultipartFile(
                "file", "learners.csv", "text/csv", "content".getBytes());

        mockMvc.perform(multipart(BASE_URL + "/bulk").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.acceptedCount").value(5))
                .andExpect(jsonPath("$.data.rejectedCount").value(1))
                .andExpect(jsonPath("$.data.errors[0].rowNumber").value(3));
    }

    @Test
    void bulkUpload_withMalformedFile_returns422() throws Exception {
        when(learnerService.bulkUpload(any()))
                .thenThrow(new MalformedCsvException("CSV is missing required column(s)"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.csv", "text/csv", "bad".getBytes());

        mockMvc.perform(multipart(BASE_URL + "/bulk").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── AC-3: GET /api/v1/admin/learners ──────────────────────────────────────

    @Test
    void getLearners_returns200WithPagedResponse() throws Exception {
        PagedResponse<LearnerResponse> page = PagedResponse.of(
                new PageImpl<>(List.of(sampleResponse), PageRequest.of(0, 20), 1));
        when(learnerService.getLearners(any(), any(), any(), any(), any())).thenReturn(page);

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].email")
                        .value("ama.owusu@learner.labgate.com"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    // ── AC-3: GET /api/v1/admin/learners/{id} ─────────────────────────────────

    @Test
    void getLearner_whenFound_returns200() throws Exception {
        when(learnerService.getLearnerById(learnerId)).thenReturn(sampleResponse);

        mockMvc.perform(get(BASE_URL + "/" + learnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(learnerId.toString()));
    }

    @Test
    void getLearner_whenNotFound_returns404() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(learnerService.getLearnerById(unknownId))
                .thenThrow(new ResourceNotFoundException("Learner not found"));

        mockMvc.perform(get(BASE_URL + "/" + unknownId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── AC-3: PUT /api/v1/admin/learners/{id} ─────────────────────────────────

    @Test
    void updateLearner_withValidData_returns200() throws Exception {
        when(learnerService.updateLearner(any(), any())).thenReturn(sampleResponse);

        mockMvc.perform(put(BASE_URL + "/" + learnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Ama Owusu-Mensah",
                                  "cohortId": "%s",
                                  "specializationId": "%s"
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── AC-3: PATCH /api/v1/admin/learners/{id}/status ────────────────────────

    @Test
    void updateLearnerStatus_withValidStatus_returns200() throws Exception {
        LearnerResponse archived = LearnerResponse.builder()
                .id(learnerId).fullName("Ama Owusu")
                .email("ama@test.com").cohortId(UUID.randomUUID())
                .cohortName("Cohort 1").specializationId(UUID.randomUUID())
                .specializationName("Data Analytics")
                .status(LearnerStatus.ARCHIVED)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();
        when(learnerService.updateLearnerStatus(any(), any())).thenReturn(archived);

        mockMvc.perform(patch(BASE_URL + "/" + learnerId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ARCHIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));
    }

    // ── AC-3: DELETE /api/v1/admin/learners/{id} ──────────────────────────────

    @Test
    void deleteLearner_whenSuccessful_returns204() throws Exception {
        doNothing().when(learnerService).deleteLearner(learnerId);

        mockMvc.perform(delete(BASE_URL + "/" + learnerId))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteLearner_whenResultsExist_returns409() throws Exception {
        doThrow(new DuplicateResourceException(
                "Learner has associated lab results. Archive the learner instead."))
                .when(learnerService).deleteLearner(learnerId);

        mockMvc.perform(delete(BASE_URL + "/" + learnerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "Learner has associated lab results. Archive the learner instead."));
    }
}
