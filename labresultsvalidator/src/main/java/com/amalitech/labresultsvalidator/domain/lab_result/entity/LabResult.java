package com.amalitech.labresultsvalidator.domain.lab_result.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import com.amalitech.labresultsvalidator.domain.csvUploads.entity.CsvUpload;
import com.amalitech.labresultsvalidator.domain.lab.entity.Lab;
import com.amalitech.labresultsvalidator.domain.learner.entity.Learner;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "lab_results",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_lab_result",
                        columnNames = {"learner_id", "lab_id", "attempt_number"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResult extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "learner_id", nullable = false)
    private Learner learner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lab_id", nullable = false)
    private Lab lab;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "csv_upload_id", nullable = false)
    private CsvUpload csvUpload;

    @Column(name = "score", nullable = false, precision = 8, scale = 2)
    private BigDecimal score;

    @Column(name = "max_score_snapshot", nullable = false, precision = 8, scale = 2)
    private BigDecimal maxScoreSnapshot;

    @Column(name = "attempt_number", nullable = false)
    private short attemptNumber;

    @Column(name = "submitted_on", nullable = false)
    private LocalDate submittedOn;

    @Column(name = "graded_by", length = 200)
    private String gradedBy;
}