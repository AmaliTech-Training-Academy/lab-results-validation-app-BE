package com.amalitech.labresultsvalidator.domain.lab_result.repository;

import com.amalitech.labresultsvalidator.domain.lab_result.entity.LabResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LabResultRepository extends JpaRepository<LabResult, UUID> {

    boolean existsByLearnerId(UUID learnerId);
}
