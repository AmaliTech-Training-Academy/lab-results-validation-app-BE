package com.amalitech.labresultsvalidator.domain.specialization.repository;

import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpecializationRepository extends JpaRepository<Specialization, UUID> {

    @EntityGraph(attributePaths = {"cohort"})
    Optional<Specialization> findByIdAndCohortId(UUID id, UUID cohortId);
}
