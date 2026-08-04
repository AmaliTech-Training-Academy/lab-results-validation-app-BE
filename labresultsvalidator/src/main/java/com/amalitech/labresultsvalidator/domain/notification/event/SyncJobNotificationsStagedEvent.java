package com.amalitech.labresultsvalidator.domain.notification.event;

import java.util.UUID;

/** Published after {@code NotificationStagingService} stages at least one PENDING notification. */
public record SyncJobNotificationsStagedEvent(UUID syncJobId) {
}