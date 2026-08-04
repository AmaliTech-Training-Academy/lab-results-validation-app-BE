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

/**
 * Links an {@link InstructorContact} (a global, person-level identity) to a cohort-scoped
 * {@link Specialization}. Unlike {@code InstructorContact}, this table follows the same
 * delete-then-recreate-per-commit lifecycle as Specializations/Modules/Labs/Learners, since a
 * specialization row itself is replaced on every reference-data commit for its cohort.
 */
@Entity
@Table(name = "instructor_specialization_assignments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstructorSpecializationAssignment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "instructor_contact_id", nullable = false)
    private UUID instructorContactId;

    @Column(name = "specialization_id", nullable = false)
    private UUID specializationId;
}