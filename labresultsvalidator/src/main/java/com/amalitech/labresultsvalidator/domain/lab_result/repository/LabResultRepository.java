package com.amalitech.labresultsvalidator.domain.lab_result.repository;

import com.amalitech.labresultsvalidator.domain.lab_result.entity.LabResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    @EntityGraph(attributePaths = {"learner", "lab"})
    @Query("SELECT r FROM LabResult r WHERE r.lab.module.id = :moduleId")
    List<LabResult> findAllByModuleId(@Param("moduleId") UUID moduleId);

    @EntityGraph(attributePaths = {"learner", "lab"})
    @Query("SELECT r FROM LabResult r WHERE r.lab.module.id = :moduleId")
    Page<LabResult> findAllByModuleId(@Param("moduleId") UUID moduleId, Pageable pageable);
}
