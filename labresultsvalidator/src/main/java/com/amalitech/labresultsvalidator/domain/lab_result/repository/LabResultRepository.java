package com.amalitech.labresultsvalidator.domain.lab_result.repository;

import com.amalitech.labresultsvalidator.domain.lab_result.entity.LabResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LabResultRepository extends JpaRepository<LabResult, UUID> {

    boolean existsByLearnerId(UUID learnerId);

    /**
     * Look up an existing result for a learner's attempt at a lab (V17). Drives the
     * insert/update/skip decision during a bulk upload reconcile.
     */
    Optional<LabResult> findByLearnerIdAndLabIdAndAttemptNumber(
        UUID learnerId, UUID labId, short attemptNumber);
}
