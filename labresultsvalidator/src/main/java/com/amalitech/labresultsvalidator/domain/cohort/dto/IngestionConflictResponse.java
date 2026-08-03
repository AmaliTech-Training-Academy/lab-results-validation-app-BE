package com.amalitech.labresultsvalidator.domain.cohort.dto;

import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionConflict;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * A held in-file duplicate row awaiting manual resolution (B10) — the REST view of
 * {@code IngestionConflict}. {@code incomingPayload} unwraps the stored jsonb snapshot of the
 * conflicting row so callers don't have to parse a nested JSON string.
 */
public record IngestionConflictResponse(
    UUID id,
    UUID ingestionRunId,
    UUID cohortId,
    UUID learnerId,
    UUID labId,
    String conflictKind,
    UUID existingResultId,
    Map<String, Object> incomingPayload,
    String status,
    UUID resolvedBy,
    OffsetDateTime resolvedAt,
    String resolutionNote,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    private static final Logger LOG = LoggerFactory.getLogger(IngestionConflictResponse.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static IngestionConflictResponse from(IngestionConflict conflict) {
        return new IngestionConflictResponse(
            conflict.getId(),
            conflict.getIngestionRunId(),
            conflict.getCohortId(),
            conflict.getLearnerId(),
            conflict.getLabId(),
            conflict.getConflictKind(),
            conflict.getExistingResultId(),
            parsePayload(conflict.getIncomingPayloadJson()),
            conflict.getStatus(),
            conflict.getResolvedBy(),
            conflict.getResolvedAt(),
            conflict.getResolutionNote(),
            conflict.getCreatedAt(),
            conflict.getUpdatedAt()
        );
    }

    private static Map<String, Object> parsePayload(String incomingPayloadJson) {
        if (incomingPayloadJson == null || incomingPayloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(incomingPayloadJson, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException ex) {
            LOG.warn("Could not parse stored incomingPayloadJson: {}", ex.getMessage());
            return Map.of();
        }
    }
}
