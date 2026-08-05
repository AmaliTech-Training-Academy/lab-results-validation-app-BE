package com.amalitech.labresultsvalidator.domain.reference.repository;

import com.amalitech.labresultsvalidator.domain.reference.entity.Lab;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabRepository extends JpaRepository<Lab, UUID> {

    List<Lab> findAllByModuleIdIn(Collection<UUID> moduleIds);

    Optional<Lab> findByModuleIdAndTitleIgnoreCase(UUID moduleId, String title);
}
