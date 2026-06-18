package com.amalitech.labresultsvalidator.domain.module.repository;

import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModuleRepository extends JpaRepository<Module, UUID> {

    List<Module> findAllById(Iterable<UUID> ids);

    /** Resolve a module within a specialization by name, case-insensitively (V12). */
    Optional<Module> findBySpecializationIdAndNameIgnoreCase(UUID specializationId, String name);

    int countBySpecializationId(UUID specializationId);

    @EntityGraph(attributePaths = {"specialization", "specialization.cohort"})
    List<Module> findAllBySpecializationId(UUID specializationId);

    @EntityGraph(attributePaths = {"specialization", "specialization.cohort"})
    List<Module> findAllBySpecializationIdAndSpecializationCohortId(UUID specializationId, UUID cohortId);

    @EntityGraph(attributePaths = {"specialization", "specialization.cohort"})
    List<Module> findAllBySpecializationCohortId(UUID cohortId);

    @Query("SELECT m.specialization.cohort.locked FROM Module m WHERE m.id = :id")
    Optional<Boolean> findCohortIsLockedById(@Param("id") UUID moduleId);

    @Override
    @EntityGraph(attributePaths = {"specialization", "specialization.cohort"})
    List<Module> findAll();
}