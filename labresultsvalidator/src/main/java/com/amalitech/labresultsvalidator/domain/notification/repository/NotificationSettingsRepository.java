package com.amalitech.labresultsvalidator.domain.notification.repository;

import com.amalitech.labresultsvalidator.domain.notification.entity.NotificationSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, UUID> {
}