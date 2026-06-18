package com.amalitech.labresultsvalidator.domain.lab_result.controller;

import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import com.amalitech.labresultsvalidator.common.csv.MalformedCsvException;
import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.GlobalExceptionHandler;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.enums.UploadStatus;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultResponse;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultUploadResponse;
import com.amalitech.labresultsvalidator.domain.lab_result.service.LabResultUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LabResultControllerTest {

    private MockMvc mockMvc;

    @Mock
    private LabResultUploadService labResultUploadService;

    @InjectMocks
    private LabResultController labResultController;

    private static final String BASE_URL = "/api/v1/lab-results";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(labResultController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    }

    @Test
    void bulkUpload_withValidFile_returns200WithCounts() throws Exception {
        LabResultUploadResponse uploadResult = LabResultUploadResponse.builder()
            .uploadId(UUID.randomUUID())
            .totalRows(6)
            .insertedCount(4)
            .updatedCount(1)
            .skippedCount(0)
            .rejectedCount(1)
            .status(UploadStatus.COMPLETED)
            .errors(List.of(new CsvRowError(3L, "SCORE", "V5", "Score 25 must be between 0 and 20")))
            .build();
        when(labResultUploadService.bulkUpload(any())).thenReturn(uploadResult);

        MockMultipartFile file = new MockMultipartFile(
            "file", "lab_results.csv", "text/csv", "content".getBytes());

        mockMvc.perform(multipart(BASE_URL + "/bulk").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.insertedCount").value(4))
            .andExpect(jsonPath("$.data.updatedCount").value(1))
            .andExpect(jsonPath("$.data.rejectedCount").value(1))
            .andExpect(jsonPath("$.data.errors[0].rowNumber").value(3))
            .andExpect(jsonPath("$.data.errors[0].field").value("SCORE"))
            .andExpect(jsonPath("$.data.errors[0].rule").value("V5"));
    }

    @Test
    void bulkUpload_withMalformedFile_returns422() throws Exception {
        when(labResultUploadService.bulkUpload(any()))
            .thenThrow(new MalformedCsvException("CSV is missing required column(s)"));

        MockMultipartFile file = new MockMultipartFile(
            "file", "bad.csv", "text/csv", "bad".getBytes());

        mockMvc.perform(multipart(BASE_URL + "/bulk").file(file))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void bulkUpload_withDuplicateFile_returns409() throws Exception {
        when(labResultUploadService.bulkUpload(any()))
            .thenThrow(new DuplicateResourceException("This file was already uploaded on ..."));

        MockMultipartFile file = new MockMultipartFile(
            "file", "dup.csv", "text/csv", "content".getBytes());

        mockMvc.perform(multipart(BASE_URL + "/bulk").file(file))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void downloadTemplate_returns200() throws Exception {
        doNothing().when(labResultUploadService).downloadTemplate(any());

        mockMvc.perform(get(BASE_URL + "/template"))
            .andExpect(status().isOk());
    }


    @Test
    void getResultsByModule_withKnownModule_returns200WithResults() throws Exception {
        UUID moduleId = UUID.randomUUID();
        UUID labId = UUID.randomUUID();
        LabResultResponse result = LabResultResponse.builder()
            .id(UUID.randomUUID())
            .learnerEmail("jane@test.com")
            .learnerName("Jane Doe")
            .labId(labId)
            .labTitle("Lab 1")
            .score(new java.math.BigDecimal("18.00"))
            .maxScoreSnapshot(new java.math.BigDecimal("20.00"))
            .attemptNumber((short) 1)
            .submittedOn(java.time.LocalDate.of(2026, 1, 15))
            .gradedBy("Dr. Smith")
            .build();

        when(labResultUploadService.getLabResultsByModule(eq(moduleId), any()))
            .thenReturn(PagedResponse.of(new PageImpl<>(List.of(result))));

        mockMvc.perform(get(BASE_URL + "/modules/" + moduleId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].learnerEmail").value("jane@test.com"))
            .andExpect(jsonPath("$.data.content[0].learnerName").value("Jane Doe"))
            .andExpect(jsonPath("$.data.content[0].labTitle").value("Lab 1"))
            .andExpect(jsonPath("$.data.content[0].attemptNumber").value(1))
            .andExpect(jsonPath("$.data.content[0].gradedBy").value("Dr. Smith"));
    }

    @Test
    void getResultsByModule_withNoResults_returns200WithEmptyList() throws Exception {
        UUID moduleId = UUID.randomUUID();
        when(labResultUploadService.getLabResultsByModule(eq(moduleId), any()))
            .thenReturn(PagedResponse.of(new PageImpl<>(List.of())));

        mockMvc.perform(get(BASE_URL + "/modules/" + moduleId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void getResultsByModule_withUnknownModule_returns404() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(labResultUploadService.getLabResultsByModule(eq(unknownId), any()))
            .thenThrow(new ResourceNotFoundException("Module not found with ID: " + unknownId));

        mockMvc.perform(get(BASE_URL + "/modules/" + unknownId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false));
    }
}
