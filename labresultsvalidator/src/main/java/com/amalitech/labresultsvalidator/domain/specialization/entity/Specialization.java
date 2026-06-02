package com.amalitech.labresultsvalidator.domain.specialization.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.learner.entity.Learner;
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

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "specializations",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_specialization_name",
            columnNames = {"cohort_id", "name"}),
        @UniqueConstraint(
            name = "uq_specialization_code",
            columnNames = {"cohort_id", "code"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Specialization extends BaseEntity {

    /** Maximum length of the specialization name. */
    private static final int NAME_MAX_LENGTH = 150;

    /** Maximum length of the specialization short code. */
    private static final int CODE_MAX_LENGTH = 20;

    /** Unique identifier for this specialization. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The cohort this specialization belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cohort_id", nullable = false)
    private Cohort cohort;

    /** Display name of this specialization. */
    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    /** Short code identifying this specialization (e.g. SWE, DA). */
    @Column(name = "code", nullable = false, length = CODE_MAX_LENGTH)
    private String code;

    /** Modules delivered under this specialization. */
    @OneToMany(mappedBy = "specialization",
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    private List<Module> modules;

    /** Learners enrolled in this specialization. */
    @OneToMany(mappedBy = "specialization",
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    private List<Learner> learners;
}
