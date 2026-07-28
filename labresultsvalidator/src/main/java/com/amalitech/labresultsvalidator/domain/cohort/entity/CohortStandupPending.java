package com.amalitech.labresultsvalidator.domain.cohort.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "cohort_standup_pending")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CohortStandupPending {

    @Id
    @Column(name = "cohort_id", nullable = false, updatable = false)
    private UUID cohortId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bundle_json", nullable = false, columnDefinition = "jsonb")
    private String bundleJson;

    @Builder.Default
    @Column(name = "passed_at", nullable = false)
    private OffsetDateTime passedAt = OffsetDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
}
