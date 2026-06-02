package com.amalitech.labresultsvalidator.domain.lab_result.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import com.amalitech.labresultsvalidator.domain.csvUploads.entity.CsvUpload;
import com.amalitech.labresultsvalidator.domain.lab.entity.Lab;
import com.amalitech.labresultsvalidator.domain.learner.entity.Learner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "lab_results",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_lab_result",
            columnNames = {"learner_id", "lab_id", "attempt_number"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResult extends BaseEntity {

    /** Decimal precision for score columns. */
    private static final int SCORE_PRECISION = 8;

    /** Maximum length for the graded-by name field. */
    private static final int GRADED_BY_MAX_LENGTH = 200;

    /** Unique identifier for this lab result. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The learner who submitted this result. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "learner_id", nullable = false)
    private Learner learner;

    /** The lab this result is for. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lab_id", nullable = false)
    private Lab lab;

    /** The CSV upload batch this result was imported from. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "csv_upload_id", nullable = false)
    private CsvUpload csvUpload;

    /** Score achieved by the learner for this attempt. */
    @Column(name = "score", nullable = false,
        precision = SCORE_PRECISION, scale = 2)
    private BigDecimal score;

    /** Snapshot of the lab max score captured at grading time. */
    @Column(name = "max_score_snapshot", nullable = false,
        precision = SCORE_PRECISION, scale = 2)
    private BigDecimal maxScoreSnapshot;

    /** Attempt number for this submission, starting at 1. */
    @Column(name = "attempt_number", nullable = false)
    private short attemptNumber;

    /** Date the learner submitted this lab for grading. */
    @Column(name = "submitted_on", nullable = false)
    private LocalDate submittedOn;

    /** Name of the person who graded this result. */
    @Column(name = "graded_by", length = GRADED_BY_MAX_LENGTH)
    private String gradedBy;
}
