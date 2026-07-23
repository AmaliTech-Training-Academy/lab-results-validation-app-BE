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

import java.util.UUID;

@Entity
@Table(name = "learners")
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

    @Column(name = "learner_id", nullable = false, unique = true, length = 50)
    private String learnerId;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Column(name = "specialization_id", nullable = false)
    private UUID specializationId;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "active";
}
