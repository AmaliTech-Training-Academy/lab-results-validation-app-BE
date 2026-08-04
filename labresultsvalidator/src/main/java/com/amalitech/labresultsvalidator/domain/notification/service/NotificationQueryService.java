package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.notification.dto.NotificationResponse;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    public Page<NotificationResponse> search(
            UUID cohortId, UUID syncJobId, String status, String type, String recipientKind, Pageable pageable) {
        return notificationRepository.search(cohortId, syncJobId, status, type, recipientKind, pageable)
            .map(n -> NotificationResponse.from(n, objectMapper));
    }

    public NotificationResponse getById(UUID id) {
        return notificationRepository.findById(id)
            .map(n -> NotificationResponse.from(n, objectMapper))
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found with id: " + id));
    }
}