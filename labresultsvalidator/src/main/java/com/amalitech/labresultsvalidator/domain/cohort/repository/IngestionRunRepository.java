package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, UUID> {

    List<IngestionRun> findBySyncJobId(UUID syncJobId);
}
