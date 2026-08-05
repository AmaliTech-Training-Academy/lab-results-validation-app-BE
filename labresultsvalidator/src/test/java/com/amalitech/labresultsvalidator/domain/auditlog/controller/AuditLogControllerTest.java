package com.amalitech.labresultsvalidator.domain.auditlog.controller;

import com.amalitech.labresultsvalidator.common.exceptions.GlobalExceptionHandler;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.auditlog.dto.AuditEventResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.IngestionRunAuditResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.IngestionRunDetailResponse;
import com.amalitech.labresultsvalidator.domain.auditlog.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuditLogControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private AuditLogController auditLogController;

    private static final String BASE_URL = "/api/v1/audit-log";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(auditLogController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    }

    @Test
    void listIngestionRuns_returns200() throws Exception {
        UUID cohortId = UUID.randomUUID();
        IngestionRunAuditResponse run = new IngestionRunAuditResponse(
            UUID.randomUUID(), cohortId, "BEM01.xlsx", "completed", "MANUAL", UUID.randomUUID(),
            10, 8, 1, 1, 0, 0, false, 0.0, OffsetDateTime.now());
        when(auditLogService.listIngestionRuns(eq(cohortId), isNull(), isNull(), isNull(), isNull(), any()))
            .thenReturn(new PageImpl<>(List.of(run), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(BASE_URL + "/ingestion-runs").param("cohortId", cohortId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].workbookFilename").value("BEM01.xlsx"));
    }

    @Test
    void getIngestionRunDetail_found_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        IngestionRunDetailResponse detail = new IngestionRunDetailResponse(
            id, UUID.randomUUID(), "BEM01.xlsx", null, null, "partial", "SCHEDULED", null,
            10, 8, 1, 1, 0, 0, false, 0.0, java.util.Map.of("rejectedRows", 1), OffsetDateTime.now());
        when(auditLogService.getIngestionRunDetail(id)).thenReturn(detail);

        mockMvc.perform(get(BASE_URL + "/ingestion-runs/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.errorReport.rejectedRows").value(1));
    }

    @Test
    void getIngestionRunDetail_notFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(auditLogService.getIngestionRunDetail(id))
            .thenThrow(new ResourceNotFoundException("Ingestion run not found with ID: " + id));

        mockMvc.perform(get(BASE_URL + "/ingestion-runs/" + id))
            .andExpect(status().isNotFound());
    }

    @Test
    void listAuditEvents_returns200() throws Exception {
        UUID cohortId = UUID.randomUUID();
        AuditEventResponse event = new AuditEventResponse(
            UUID.randomUUID(), "LINK_SUBMITTED", cohortId, UUID.randomUUID(), null, OffsetDateTime.now());
        Page<AuditEventResponse> page = new PageImpl<>(List.of(event), PageRequest.of(0, 20), 1);
        when(auditLogService.listAuditEvents(eq(cohortId), eq("LINK_SUBMITTED"), isNull(), isNull(), any()))
            .thenReturn(page);

        mockMvc.perform(get(BASE_URL + "/audit-events")
                .param("cohortId", cohortId.toString())
                .param("eventType", "LINK_SUBMITTED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].eventType").value("LINK_SUBMITTED"));
    }

    @Test
    void getAuditEventDetail_found_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        AuditEventResponse detail = new AuditEventResponse(
            id, "COHORT_CREATED", UUID.randomUUID(), UUID.randomUUID(),
            java.util.Map.of("cohortName", "Cohort 2026"), OffsetDateTime.now());
        when(auditLogService.getAuditEventDetail(id)).thenReturn(detail);

        mockMvc.perform(get(BASE_URL + "/audit-events/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.eventType").value("COHORT_CREATED"))
            .andExpect(jsonPath("$.data.payload.cohortName").value("Cohort 2026"));
    }

    @Test
    void getAuditEventDetail_notFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(auditLogService.getAuditEventDetail(id))
            .thenThrow(new ResourceNotFoundException("Audit event not found with ID: " + id));

        mockMvc.perform(get(BASE_URL + "/audit-events/" + id))
            .andExpect(status().isNotFound());
    }
}
