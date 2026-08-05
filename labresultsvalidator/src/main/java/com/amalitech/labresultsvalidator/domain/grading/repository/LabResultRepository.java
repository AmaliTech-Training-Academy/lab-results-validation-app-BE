package com.amalitech.labresultsvalidator.domain.grading.repository;

import com.amalitech.labresultsvalidator.domain.grading.entity.LabResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabResultRepository extends JpaRepository<LabResult, UUID> {

    Optional<LabResult> findByLearnerIdAndLabId(UUID learnerId, UUID labId);

    /**
     * Superset fetch for batch classification (B8) — matches any row whose {@code learnerId} is
     * in the given set AND whose {@code labId} is in the given set, not just exact pairs. Callers
     * must re-key/filter to the exact {@code (learnerId, labId)} pairs they need.
     */
    List<LabResult> findByLearnerIdInAndLabIdIn(Collection<UUID> learnerIds, Collection<UUID> labIds);
}
