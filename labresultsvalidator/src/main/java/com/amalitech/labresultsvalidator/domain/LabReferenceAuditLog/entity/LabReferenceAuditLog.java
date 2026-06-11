
package com.amalitech.labresultsvalidator.domain.LabReferenceAuditLog.entity;

import com.amalitech.labresultsvalidator.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "lab_reference_audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabReferenceAuditLog {

    /** Maximum length for table and field name identifiers. */
    private static final int IDENTIFIER_MAX_LENGTH = 100;

    /** Maximum length for an email address. */
    private static final int EMAIL_MAX_LENGTH = 254;

    /** Unique identifier for this audit log entry. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Name of the database table where the change occurred. */
    @Column(name = "table_name", nullable = false,
        length = IDENTIFIER_MAX_LENGTH)
    private String tableName;

    /** Primary key of the row that was changed. */
    @Column(name = "record_id", nullable = false)
    private UUID recordId;

    /** Name of the column that was modified. */
    @Column(name = "field_name", nullable = false,
        length = IDENTIFIER_MAX_LENGTH)
    private String fieldName;

    /** Value of the field before the change. */
    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    /** Value of the field after the change. */
    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    /** User who performed the change; null if that user was deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by")
    private User changedBy;

    /** Email of the actor at change time, retained after deletion. */
    @Column(name = "deleted_user_email", length = EMAIL_MAX_LENGTH)
    private String deletedUserEmail;

    /** Timestamp when this change was recorded. */
    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private OffsetDateTime changedAt;

    /** Optional reason provided for making this change. */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;
}
