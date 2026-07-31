package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.LabResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabResultRepository extends JpaRepository<LabResult, UUID> {

    Optional<LabResult> findBySubmittedOnAndNspName(LocalDate submittedOn, String nspName);

    /**
     * Superset fetch for batch classification (B8) — matches any row whose {@code submittedOn} is
     * in the given set AND whose {@code nspName} is in the given set, not just exact pairs. Callers
     * must re-key/filter to the exact {@code (submittedOn, nspName)} pairs they need.
     */
    List<LabResult> findBySubmittedOnInAndNspNameIn(Collection<LocalDate> submittedOns, Collection<String> nspNames);
}
