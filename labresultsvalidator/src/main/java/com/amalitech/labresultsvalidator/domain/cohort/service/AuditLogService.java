package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.AuditEventResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.IngestionRunAuditResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.IngestionRunDetailResponse;
import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionRun;
import com.amalitech.labresultsvalidator.domain.cohort.repository.AuditEventRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.IngestionRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only admin audit-log browsing (PRD Epic D, D5) across every cohort and run — distinct from
 * any future per-run Run-Review moderation screen (C6). No write methods on purpose (D6 AC1).
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final IngestionRunRepository ingestionRunRepository;
    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    public Page<IngestionRunAuditResponse> listIngestionRuns(
            UUID cohortId, String status, OffsetDateTime from, OffsetDateTime to,
            UUID instructorContactId, Pageable pageable) {
        return ingestionRunRepository
            .search(cohortId, status, from, to, instructorContactId, pageable)
            .map(IngestionRunAuditResponse::from);
    }

    public IngestionRunDetailResponse getIngestionRunDetail(UUID id) {
        IngestionRun run = ingestionRunRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Ingestion run not found with ID: " + id));
        return IngestionRunDetailResponse.from(run, objectMapper);
    }

    public Page<AuditEventResponse> listAuditEvents(
            UUID cohortId, String eventType, OffsetDateTime from, OffsetDateTime to, Pageable pageable) {
        return auditEventRepository
            .search(cohortId, eventType, from, to, pageable)
            .map(event -> AuditEventResponse.from(event, objectMapper));
    }
}
