package com.amalitech.labresultsvalidator.domain.specialization.repository;

import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpecializationRepository extends JpaRepository<Specialization, UUID> {
    boolean existsByCohortIdAndName(UUID cohortId, String name);

    boolean existsByCohortIdAndCode(UUID cohortId, String code);

    @EntityGraph(attributePaths = {"cohort"})
    Optional<Specialization> findByIdAndCohortId(UUID id, UUID cohortId);

    @EntityGraph(attributePaths = {"cohort"})
    Optional<Specialization> findByCohortIdAndNameIgnoreCase(UUID cohortId, String name);

    @EntityGraph(attributePaths = {"cohort"})
    Page<Specialization> findAllByOrderByNameAsc(Pageable pageable);

    @EntityGraph(attributePaths = {"cohort"})
    Page<Specialization> findAllByCohortIdOrderByNameAsc(UUID cohortId, Pageable pageable);
}
