package com.amalitech.labresultsvalidator.domain.cohort.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import com.amalitech.labresultsvalidator.domain.learner.entity.Learner;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "cohorts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cohort extends BaseEntity {

    /** Maximum length of the cohort name column. */
    private static final int NAME_MAX_LENGTH = 150;

    /** Unique identifier for this cohort. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Human-readable name of this cohort. */
    @Column(name = "name", nullable = false, unique = true,
        length = NAME_MAX_LENGTH)
    private String name;

    /** Date on which the cohort begins. */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Date on which the cohort ends. */
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** Whether this cohort is currently active. */
    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Specializations offered within this cohort. */
    @OneToMany(mappedBy = "cohort",
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    private List<Specialization> specializations;

    /** Learners enrolled in this cohort. */
    @OneToMany(mappedBy = "cohort",
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    private List<Learner> learners;
}
