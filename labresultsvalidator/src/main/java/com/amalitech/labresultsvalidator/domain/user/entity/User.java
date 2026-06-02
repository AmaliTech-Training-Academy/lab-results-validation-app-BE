package com.amalitech.labresultsvalidator.domain.user.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import com.amalitech.labresultsvalidator.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    /** Maximum length for email address columns. */
    private static final int EMAIL_MAX_LENGTH = 254;

    /** Unique identifier for this user. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Login email address of this user. */
    @Column(name = "email", nullable = false, unique = true,
        length = EMAIL_MAX_LENGTH)
    private String email;

    /** BCrypt hash of the user's password. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** Role that determines this user's access level. */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role;

    /** Whether this user account is currently active. */
    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Whether the user must change their password on next login. */
    @Builder.Default
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = true;
}
