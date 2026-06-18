package com.amalitech.labresultsvalidator.domain.module.repository;

import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ModuleRepository extends JpaRepository<Module, UUID> {

    List<Module> findAllById(Iterable<UUID> ids);

    /** Resolve a module within a specialization by name, case-insensitively (V12). */
    Optional<Module> findBySpecializationIdAndNameIgnoreCase(UUID specializationId, String name);

    int countBySpecializationId(UUID specializationId);

    boolean existsBySpecializationIdAndNameAndIdNot(UUID specializationId, String name, UUID id);

    @EntityGraph(attributePaths = {"specialization", "specialization.cohort"})
    List<Module> findAllBySpecializationId(UUID specializationId);

    @EntityGraph(attributePaths = {"specialization", "specialization.cohort"})
    Page<Module> findAllBySpecializationId(UUID specializationId, Pageable pageable);

    @EntityGraph(attributePaths = {"specialization", "specialization.cohort"})
    List<Module> findAllBySpecializationIdAndSpecializationCohortId(UUID specializationId, UUID cohortId);

    @EntityGraph(attributePaths = {"specialization", "specialization.cohort"})
    Page<Module> findAllBySpecializationIdAndSpecializationCohortId(
            UUID specializationId, UUID cohortId, Pageable pageable);

    @EntityGraph(attributePaths = {"specialization", "specialization.cohort"})
    List<Module> findAllBySpecializationCohortId(UUID cohortId);

    @EntityGraph(attributePaths = {"specialization", "specialization.cohort"})
    Page<Module> findAllBySpecializationCohortId(UUID cohortId, Pageable pageable);

    @Query("SELECT m.specialization.cohort.locked FROM Module m WHERE m.id = :id")
    Optional<Boolean> findCohortIsLockedById(@Param("id") UUID moduleId);

    @Override
    @EntityGraph(attributePaths = {"specialization", "specialization.cohort"})
    List<Module> findAll();

    @Override
    @EntityGraph(attributePaths = {"specialization", "specialization.cohort"})
    Page<Module> findAll(Pageable pageable);
}