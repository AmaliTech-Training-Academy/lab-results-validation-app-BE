package com.amalitech.labresultsvalidator.domain.auditlog.service;

import com.amalitech.labresultsvalidator.domain.auditlog.entity.AuditEvent;
import com.amalitech.labresultsvalidator.domain.auditlog.repository.AuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class AuditEventService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditEventService.class);

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    public AuditEventService(AuditEventRepository auditEventRepository, ObjectMapper objectMapper) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
    }

    public void record(String eventType, UUID cohortId, UUID actorUserId, Object payload) {
        String payloadJson = null;
        if (payload != null) {
            try {
                payloadJson = objectMapper.writeValueAsString(payload);
            } catch (JsonProcessingException ex) {
                LOG.warn("Failed to serialize audit payload for event {}: {}", eventType, ex.getMessage());
                payloadJson = "{\"error\":\"serialization failed\"}";
            }
        }

        AuditEvent event = AuditEvent.builder()
            .eventType(eventType)
            .cohortId(cohortId)
            .actorUserId(actorUserId)
            .occurredAt(OffsetDateTime.now())
            .payloadJson(payloadJson)
            .build();

        auditEventRepository.save(event);
    }
}
