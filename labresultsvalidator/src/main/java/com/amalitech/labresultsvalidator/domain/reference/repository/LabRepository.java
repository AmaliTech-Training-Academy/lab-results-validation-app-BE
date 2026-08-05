package com.amalitech.labresultsvalidator.domain.reference.repository;

import com.amalitech.labresultsvalidator.domain.reference.dto.LabModuleName;
import com.amalitech.labresultsvalidator.domain.reference.entity.Lab;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabRepository extends JpaRepository<Lab, UUID> {

    List<Lab> findAllByModuleIdIn(Collection<UUID> moduleIds);

    Optional<Lab> findByModuleIdAndTitleIgnoreCase(UUID moduleId, String title);

    /**
     * C3 AC2 — every lab title in a cohort paired with its owning module's name, so a digest can group
     * rows by module in one query instead of a lookup per row.
     *
     * <p>An explicit join because {@code Lab.moduleId} and {@code LabModule.specializationId} are plain
     * {@code UUID} columns, not {@code @ManyToOne}, so no derived query can traverse them. A projection
     * rather than entities so one digest does not pull every lab and module in the cohort into the
     * persistence context.
     */
    @Query("SELECT new com.amalitech.labresultsvalidator.domain.reference.dto.LabModuleName("
        + "l.title, m.name) "
        + "FROM Lab l, LabModule m, Specialization s "
        + "WHERE l.moduleId = m.id AND m.specializationId = s.id AND s.cohortId = :cohortId")
    List<LabModuleName> findLabModuleNamesByCohortId(@Param("cohortId") UUID cohortId);
}
