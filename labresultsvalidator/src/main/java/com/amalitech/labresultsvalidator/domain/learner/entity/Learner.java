package com.amalitech.labresultsvalidator.domain.learner.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.lab_result.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.enums.LearnerStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "learners")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Learner extends BaseEntity {

    /** Maximum length of the learner's full name. */
    private static final int FULL_NAME_MAX_LENGTH = 200;

    /** Maximum length of an email address. */
    private static final int EMAIL_MAX_LENGTH = 254;

    /** Unique identifier for this learner. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Full name of the learner. */
    @Column(name = "full_name", nullable = false,
        length = FULL_NAME_MAX_LENGTH)
    private String fullName;

    /** Email address of the learner. */
    @Column(name = "email", nullable = false, unique = true,
        length = EMAIL_MAX_LENGTH)
    private String email;

    /** The cohort this learner is enrolled in. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cohort_id", nullable = false)
    private Cohort cohort;

    /** The specialization track this learner follows. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "specialization_id", nullable = false)
    private Specialization specialization;

    /** Current enrolment status of this learner. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LearnerStatus status = LearnerStatus.ACTIVE;

    /** Lab results submitted by this learner. */
    @OneToMany(mappedBy = "learner",
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    private List<LabResult> labResults;
}
