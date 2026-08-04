package com.amalitech.labresultsvalidator.domain.notification.dto;

import com.amalitech.labresultsvalidator.domain.notification.entity.NotificationSettings;
import lombok.Builder;

@Builder
public record NotificationSettingsResponse(boolean autoSendInstructorEmails) {
    public static NotificationSettingsResponse from(NotificationSettings settings) {
        return new NotificationSettingsResponse(settings.isAutoSendInstructorEmails());
    }
}