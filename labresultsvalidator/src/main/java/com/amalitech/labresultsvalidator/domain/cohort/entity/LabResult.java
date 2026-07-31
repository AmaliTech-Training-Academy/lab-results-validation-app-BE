package com.amalitech.labresultsvalidator.domain.cohort.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A committed grading result. Row identity is {@code (learnerId, labId)} — see
 * {@code V20__lab_result_identity_learner_lab.sql} — since those are resolved during validation
 * and don't change between ingestion runs, unlike {@code submittedOn} (a re-grade can land on a
 * new date). {@code submittedOn}/{@code score} feed change detection ({@code rowValueHash})
 * instead.
 */
@Entity
@Table(name = "lab_results")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabResult extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    @Column(name = "lab_id", nullable = false)
    private UUID labId;

    @Column(name = "ingestion_run_id", nullable = false)
    private UUID ingestionRunId;

    @Column(name = "instructor_contact_id")
    private UUID instructorContactId;

    /** Raw "Name of NSP" text as read from the sheet, normalized (trim + lowercase). */
    @Column(name = "nsp_name", nullable = false, length = 255)
    private String nspName;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal score;

    @Builder.Default
    @Column(name = "max_score_snapshot", nullable = false, precision = 8, scale = 2)
    private BigDecimal maxScoreSnapshot = BigDecimal.valueOf(100);

    /** Review Date. */
    @Column(name = "submitted_on", nullable = false)
    private LocalDate submittedOn;

    /** Change-detection fingerprint: hash(submittedOn, score). */
    @Column(name = "row_value_hash", nullable = false, length = 64)
    private String rowValueHash;
}
