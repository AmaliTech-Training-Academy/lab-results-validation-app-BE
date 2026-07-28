package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CohortRepository extends JpaRepository<Cohort, UUID> {

    boolean existsByNameIgnoreCase(String name);

    Optional<Cohort> findByIdAndIsActiveTrue(UUID id);

    List<Cohort> findAllByLifecycleStateAndIsActiveTrue(CohortLifecycleState lifecycleState);
}
