package com.amalitech.labresultsvalidator.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Outbox row for an instructor grading digest or admin alert. Staging (creating this row as
 * {@code PENDING}) is fully decoupled from dispatch (actually sending the email) — see
 * {@code NotificationStagingService}/{@code NotificationDispatchService}.
 *
 * <p>No {@code created_by}/{@code updated_by} columns exist on this table, so — like
 * {@code IngestionRun} — this entity does not extend {@code BaseEntity}.
 *
 * <p>{@code type}: {@code instructor_digest} / {@code admin_run_digest} / {@code standup_failure} /
 * {@code high_failure} / {@code conflict_alert} / {@code stood_up} (chk_notif_type; only the first
 * two are populated today). {@code recipientKind}: {@code instructor} / {@code admin}
 * (chk_notif_kind) — {@code recipientKind = instructor} requires exactly {@code recipientInstructorId}
 * set and {@code recipientUserId} null, and vice versa for {@code admin} (chk_notif_recipient).
 * {@code dispatchPolicy}: {@code AUTO} / {@code HELD} (chk_notif_policy).
 * {@code status}: {@code PENDING} / {@code SENT} / {@code SKIPPED} / {@code FAILED}
 * (chk_notif_status) — {@code SKIPPED} is reached only via a manual dismiss of a
 * {@code PENDING} notification, see {@code NotificationDispatchService#dismiss}.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "ingestion_run_id")
    private UUID ingestionRunId;

    @Column(name = "cohort_id")
    private UUID cohortId;

    @Column(name = "sync_job_id")
    private UUID syncJobId;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(name = "recipient_kind", nullable = false, length = 20)
    private String recipientKind;

    @Column(name = "recipient_instructor_id")
    private UUID recipientInstructorId;

    @Column(name = "recipient_user_id")
    private UUID recipientUserId;

    @Column(name = "dispatch_policy", nullable = false, length = 10)
    private String dispatchPolicy;

    @Column
    private String subject;

    @Column
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb")
    private String payloadJson;

    @Builder.Default
    @Column(nullable = false, length = 10)
    private String status = "PENDING";

    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "sent_by")
    private UUID sentBy;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "dismissed_by")
    private UUID dismissedBy;

    @Column(name = "dismissed_at")
    private OffsetDateTime dismissedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}