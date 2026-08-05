package com.amalitech.labresultsvalidator.domain.reference.repository;

import com.amalitech.labresultsvalidator.domain.reference.entity.LabModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabModuleRepository extends JpaRepository<LabModule, UUID> {

    List<LabModule> findAllBySpecializationIdIn(Collection<UUID> specializationIds);

    Optional<LabModule> findBySpecializationIdAndCode(UUID specializationId, String code);
}
