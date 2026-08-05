package com.amalitech.labresultsvalidator.domain.notification.dto;

import com.amalitech.labresultsvalidator.domain.grading.dto.RowIssueSummary;
import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Builder
public record NotificationResponse(
    UUID id,
    UUID cohortId,
    UUID syncJobId,
    UUID ingestionRunId,
    String type,
    String recipientKind,
    UUID recipientInstructorId,
    UUID recipientUserId,
    String dispatchPolicy,
    String subject,
    String status,
    String errorDetail,
    OffsetDateTime sentAt,
    UUID dismissedBy,
    OffsetDateTime dismissedAt,
    OffsetDateTime createdAt,
    List<RowIssueSummary> issues
) {
    public static NotificationResponse from(Notification notification, ObjectMapper objectMapper) {
        return NotificationResponse.builder()
            .id(notification.getId())
            .cohortId(notification.getCohortId())
            .syncJobId(notification.getSyncJobId())
            .ingestionRunId(notification.getIngestionRunId())
            .type(notification.getType())
            .recipientKind(notification.getRecipientKind())
            .recipientInstructorId(notification.getRecipientInstructorId())
            .recipientUserId(notification.getRecipientUserId())
            .dispatchPolicy(notification.getDispatchPolicy())
            .subject(notification.getSubject())
            .status(notification.getStatus())
            .errorDetail(notification.getErrorDetail())
            .sentAt(notification.getSentAt())
            .dismissedBy(notification.getDismissedBy())
            .dismissedAt(notification.getDismissedAt())
            .createdAt(notification.getCreatedAt())
            .issues(parseIssues(notification.getPayloadJson(), objectMapper))
            .build();
    }

    private static List<RowIssueSummary> parseIssues(String payloadJson, ObjectMapper objectMapper) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<List<RowIssueSummary>>() { });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }
}