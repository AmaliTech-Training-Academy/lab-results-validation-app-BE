package com.amalitech.labresultsvalidator.domain.lab.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import com.amalitech.labresultsvalidator.domain.lab_result.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "labs",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_lab_title",
            columnNames = {"module_id", "title"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lab extends BaseEntity {

    /** Maximum character length for the lab title. */
    private static final int TITLE_MAX_LENGTH = 200;

    /** Decimal precision for the max score column. */
    private static final int MAX_SCORE_PRECISION = 8;

    /** Unique identifier for this lab. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The module this lab belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id", nullable = false)
    private Module module;

    /** Title of this lab exercise. */
    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    /** Maximum achievable score for this lab. */
    @Column(name = "max_score", nullable = false,
        precision = MAX_SCORE_PRECISION, scale = 2)
    private BigDecimal maxScore;

    /** Whether this lab definition is locked from further edits. */
    @Builder.Default
    @Column(name = "is_immutable", nullable = false)
    private boolean immutable = false;

    /** Lab results submitted for this lab. */
    @OneToMany(mappedBy = "lab",
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    private List<LabResult> labResults;
}
