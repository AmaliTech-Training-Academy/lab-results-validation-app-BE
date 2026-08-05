package com.amalitech.labresultsvalidator.domain.reference.repository;

import com.amalitech.labresultsvalidator.domain.reference.entity.Specialization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpecializationRepository extends JpaRepository<Specialization, UUID> {

    List<Specialization> findAllByCohortId(UUID cohortId);

    void deleteAllByCohortId(UUID cohortId);
}
