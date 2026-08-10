package com.amalitech.labresultsvalidator.domain.reference.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "learners", uniqueConstraints = {
    @UniqueConstraint(name = "uq_learners_cohort_email", columnNames = {"cohort_id", "email"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Learner extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    // Unique per cohort, not globally — see uq_learners_cohort_email (V30). No separate learner_id
    // column exists (dropped in V33) — it was always set to this same value, never a distinct
    // external identifier (see V33's comment).
    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Column(name = "specialization_id", nullable = false)
    private UUID specializationId;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "active";
}
