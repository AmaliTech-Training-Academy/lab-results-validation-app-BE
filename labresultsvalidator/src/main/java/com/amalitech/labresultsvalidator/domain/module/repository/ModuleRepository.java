package com.amalitech.labresultsvalidator.domain.module.repository;

import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ModuleRepository extends JpaRepository<Module, UUID> {

    List<Module> findAllById(Iterable<UUID> ids);
}