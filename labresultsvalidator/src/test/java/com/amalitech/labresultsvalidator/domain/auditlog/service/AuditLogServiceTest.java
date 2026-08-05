package com.amalitech.labresultsvalidator.domain.auditlog.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.auditlog.dto.AuditEventResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.IngestionRunAuditResponse;
import com.amalitech.labresultsvalidator.domain.grading.dto.IngestionRunDetailResponse;
import com.amalitech.labresultsvalidator.domain.auditlog.entity.AuditEvent;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionRun;
import com.amalitech.labresultsvalidator.domain.auditlog.repository.AuditEventRepository;
import com.amalitech.labresultsvalidator.domain.grading.repository.IngestionRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private IngestionRunRepository ingestionRunRepository;

    @Mock
    private AuditEventRepository auditEventRepository;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(ingestionRunRepository, auditEventRepository, new ObjectMapper());
    }

    @Test
    void listIngestionRuns_passesFiltersThroughToRepository() {
        UUID cohortId = UUID.randomUUID();
        UUID syncJobId = UUID.randomUUID();
        UUID instructorContactId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now().minusDays(7);
        OffsetDateTime to = OffsetDateTime.now();
        IngestionRun run = IngestionRun.builder()
            .id(UUID.randomUUID())
            .cohortId(cohortId)
            .syncJobId(syncJobId)
            .workbookFilename("BEM01.xlsx")
            .status("completed")
            .triggerType("MANUAL")
            .runAt(OffsetDateTime.now())
            .build();
        Page<IngestionRun> page = new PageImpl<>(List.of(run));
        when(ingestionRunRepository.search(cohortId, "completed", from, to, instructorContactId, Pageable.unpaged()))
            .thenReturn(page);

        Page<IngestionRunAuditResponse> result = auditLogService.listIngestionRuns(
            cohortId, "completed", from, to, instructorContactId, Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).workbookFilename()).isEqualTo("BEM01.xlsx");
        assertThat(result.getContent().get(0).syncJobId()).isEqualTo(syncJobId);
    }

    @Test
    void getIngestionRunDetail_found_parsesErrorReportJson() {
        UUID id = UUID.randomUUID();
        IngestionRun run = IngestionRun.builder()
            .id(id)
            .cohortId(UUID.randomUUID())
            .workbookFilename("BEM01.xlsx")
            .status("partial")
            .triggerType("SCHEDULED")
            .errorReportJson("{\"rejectedRows\":2}")
            .runAt(OffsetDateTime.now())
            .build();
        when(ingestionRunRepository.findById(id)).thenReturn(Optional.of(run));

        IngestionRunDetailResponse detail = auditLogService.getIngestionRunDetail(id);

        assertThat(detail.errorReport()).isEqualTo(Map.of("rejectedRows", 2));
    }

    @Test
    void getIngestionRunDetail_notFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(ingestionRunRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auditLogService.getIngestionRunDetail(id))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listAuditEvents_passesFiltersThroughToRepository() {
        UUID cohortId = UUID.randomUUID();
        AuditEvent event = AuditEvent.builder()
            .id(UUID.randomUUID())
            .eventType("LINK_SUBMITTED")
            .cohortId(cohortId)
            .occurredAt(OffsetDateTime.now())
            .payloadJson("{\"folderUrl\":\"https://sharepoint/x\"}")
            .build();
        Page<AuditEvent> page = new PageImpl<>(List.of(event));
        when(auditEventRepository.search(any(), any(), any(), any(), any())).thenReturn(page);

        Page<AuditEventResponse> result = auditLogService.listAuditEvents(
            cohortId, "LINK_SUBMITTED", null, null, Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).eventType()).isEqualTo("LINK_SUBMITTED");
        assertThat(result.getContent().get(0).payload()).isEqualTo(Map.of("folderUrl", "https://sharepoint/x"));
    }

    @Test
    void getAuditEventDetail_found_parsesPayloadJson() {
        UUID id = UUID.randomUUID();
        AuditEvent event = AuditEvent.builder()
            .id(id)
            .eventType("COHORT_CREATED")
            .cohortId(UUID.randomUUID())
            .occurredAt(OffsetDateTime.now())
            .payloadJson("{\"cohortName\":\"Cohort 2026\"}")
            .build();
        when(auditEventRepository.findById(id)).thenReturn(Optional.of(event));

        AuditEventResponse detail = auditLogService.getAuditEventDetail(id);

        assertThat(detail.eventType()).isEqualTo("COHORT_CREATED");
        assertThat(detail.payload()).isEqualTo(Map.of("cohortName", "Cohort 2026"));
    }

    @Test
    void getAuditEventDetail_notFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(auditEventRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auditLogService.getAuditEventDetail(id))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
