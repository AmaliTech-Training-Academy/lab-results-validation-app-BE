package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.LabResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface LabResultRepository extends JpaRepository<LabResult, UUID> {

    Optional<LabResult> findBySubmittedOnAndNspName(LocalDate submittedOn, String nspName);
}
