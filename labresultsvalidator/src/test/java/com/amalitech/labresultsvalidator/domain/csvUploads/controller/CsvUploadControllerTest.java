package com.amalitech.labresultsvalidator.domain.csvUploads.controller;

import com.amalitech.labresultsvalidator.common.exceptions.GlobalExceptionHandler;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.csvUploads.dto.CsvUploadResponse;
import com.amalitech.labresultsvalidator.domain.csvUploads.service.CsvUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CsvUploadControllerTest {

    private MockMvc mockMvc;

    @Mock
    private CsvUploadService csvUploadService;

    @InjectMocks
    private CsvUploadController csvUploadController;

    private static final String BASE_URL = "/api/v1/admin/csv-uploads";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(csvUploadController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    private CsvUploadResponse buildResponse(UUID id) {
        return CsvUploadResponse.builder()
                .id(id)
                .uploadedByEmail("instructor@test.com")
                .filename("results.csv")
                .fileSha256("abc123def456abc123def456abc123def456abc123def456abc123def456abcd")
                .uploadedAt(OffsetDateTime.now())
                .totalRows(100)
                .acceptedRows(90)
                .rejectedRows(10)
                .status("COMPLETED")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    // --- GET /api/v1/admin/csv-uploads ---

    @Test
    void listUploads_returns200WithPagedContent() throws Exception {
        UUID id = UUID.randomUUID();
        CsvUploadResponse dto = buildResponse(id);
        PagedResponse<CsvUploadResponse> paged = PagedResponse.of(
                new PageImpl<>(List.of(dto), PageRequest.of(0, 10), 1));
        when(csvUploadService.ListUploads(any(Pageable.class))).thenReturn(paged);

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("CSV uploads retrieved successfully"))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content[0].uploadedByEmail").value("instructor@test.com"))
                .andExpect(jsonPath("$.data.content[0].filename").value("results.csv"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void listUploads_whenEmpty_returns200WithEmptyContent() throws Exception {
        PagedResponse<CsvUploadResponse> empty = PagedResponse.of(
                new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        when(csvUploadService.ListUploads(any(Pageable.class))).thenReturn(empty);

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    // --- GET /api/v1/admin/csv-uploads/{id} ---

    @Test
    void getUploadById_whenFound_returns200WithUpload() throws Exception {
        UUID id = UUID.randomUUID();
        CsvUploadResponse dto = buildResponse(id);
        when(csvUploadService.getUploadById(eq(id))).thenReturn(dto);

        mockMvc.perform(get(BASE_URL + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("CSV upload retrieved successfully"))
                .andExpect(jsonPath("$.data.filename").value("results.csv"))
                .andExpect(jsonPath("$.data.uploadedByEmail").value("instructor@test.com"))
                .andExpect(jsonPath("$.data.totalRows").value(100))
                .andExpect(jsonPath("$.data.acceptedRows").value(90))
                .andExpect(jsonPath("$.data.rejectedRows").value(10))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void getUploadById_whenNotFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(csvUploadService.getUploadById(eq(id)))
                .thenThrow(new ResourceNotFoundException("CSV upload not found with id: " + id));

        mockMvc.perform(get(BASE_URL + "/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    // --- GET /api/v1/admin/csv-uploads/{id}/error-report ---

    @Test
    void getErrorReport_whenReportExists_returns200WithReport() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> report = Map.of("row", 5, "field", "SCORE", "message", "Invalid score value");
        when(csvUploadService.getErrorReport(eq(id))).thenReturn(report);

        mockMvc.perform(get(BASE_URL + "/" + id + "/error-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Error report retrieved successfully"))
                .andExpect(jsonPath("$.data.field").value("SCORE"))
                .andExpect(jsonPath("$.data.message").value("Invalid score value"));
    }

    @Test
    void getErrorReport_whenUploadNotFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(csvUploadService.getErrorReport(eq(id)))
                .thenThrow(new ResourceNotFoundException("Upload with id '" + id + "' not found"));

        mockMvc.perform(get(BASE_URL + "/" + id + "/error-report"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getErrorReport_whenNoReportAvailable_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(csvUploadService.getErrorReport(eq(id)))
                .thenThrow(new ResourceNotFoundException("No error report found for upload '" + id + "'"));

        mockMvc.perform(get(BASE_URL + "/" + id + "/error-report"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}