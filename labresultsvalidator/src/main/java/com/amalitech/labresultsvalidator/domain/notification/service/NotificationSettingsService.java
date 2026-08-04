package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.domain.notification.entity.NotificationSettings;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationSettingsService {

    private final NotificationSettingsRepository repository;

    @Transactional(readOnly = true)
    public NotificationSettings getSettings() {
        return repository.findById(NotificationSettings.SINGLETON_ID)
            .orElseThrow(() -> new IllegalStateException(
                "notification_settings singleton row is missing — check migration V24 ran."));
    }

    @Transactional
    public NotificationSettings updateAutoSendInstructorEmails(boolean autoSendInstructorEmails, UUID actorId) {
        NotificationSettings settings = getSettings();
        settings.setAutoSendInstructorEmails(autoSendInstructorEmails);
        settings.setUpdatedBy(actorId);
        return repository.save(settings);
    }
}