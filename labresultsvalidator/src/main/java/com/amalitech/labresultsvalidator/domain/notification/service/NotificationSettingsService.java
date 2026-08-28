package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.domain.notification.entity.NotificationSettings;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationSettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationSettingsService.class);

    private final NotificationSettingsRepository repository;

    @Transactional(readOnly = true)
    public NotificationSettings getSettings() {
        return repository.findById(NotificationSettings.SINGLETON_ID)
            .orElseThrow(() -> {
                // The migration-reference detail stays in the log, not the exception message — this
                // is an internal misconfiguration, not something a caller can act on.
                LOG.error("notification_settings singleton row is missing — check migration V24 ran.");
                return new IllegalStateException("Notification settings are not configured.");
            });
    }

    @Transactional
    public NotificationSettings updateAutoSendInstructorEmails(boolean autoSendInstructorEmails, UUID actorId) {
        NotificationSettings settings = getSettings();
        settings.setAutoSendInstructorEmails(autoSendInstructorEmails);
        settings.setUpdatedBy(actorId);
        return repository.save(settings);
    }
}