package com.amalitech.labresultsvalidator.domain.auditlog.dto;

import com.amalitech.labresultsvalidator.domain.auditlog.entity.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditEventResponse(
    UUID id,
    String eventType,
    UUID cohortId,
    UUID actorUserId,
    Object payload,
    OffsetDateTime occurredAt
) {
    private static final Logger LOG = LoggerFactory.getLogger(AuditEventResponse.class);

    public static AuditEventResponse from(AuditEvent event, ObjectMapper objectMapper) {
        return new AuditEventResponse(
            event.getId(),
            event.getEventType(),
            event.getCohortId(),
            event.getActorUserId(),
            parseJson(event.getPayloadJson(), objectMapper),
            event.getOccurredAt());
    }

    private static Object parseJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            LOG.warn("Failed to parse audit event payloadJson: {}", ex.getMessage());
            return json;
        }
    }
}
