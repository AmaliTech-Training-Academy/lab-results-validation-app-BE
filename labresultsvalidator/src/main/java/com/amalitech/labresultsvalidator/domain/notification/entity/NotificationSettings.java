package com.amalitech.labresultsvalidator.domain.notification.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Singleton settings row — physically enforced as one row by a fixed-id CHECK constraint on the
 * PK (see V24 migration). {@link #SINGLETON_ID} is the only id this table will ever contain.
 */
@Entity
@Table(name = "notification_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationSettings extends BaseEntity {

    public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Builder.Default
    @Column(name = "auto_send_instructor_emails", nullable = false)
    private boolean autoSendInstructorEmails = false;
}