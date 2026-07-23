package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Learner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LearnerRepository extends JpaRepository<Learner, UUID> {

    List<Learner> findAllByCohortId(UUID cohortId);

    boolean existsByLearnerIdAndCohortId(String learnerId, UUID cohortId);

    Optional<Learner> findByLearnerIdAndCohortId(String learnerId, UUID cohortId);

    void deleteAllByCohortId(UUID cohortId);
}
