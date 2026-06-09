package com.amalitech.labresultsvalidator.domain.learner.repository;

import com.amalitech.labresultsvalidator.domain.learner.entity.Learner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LearnerRepository
        extends JpaRepository<Learner, UUID>, JpaSpecificationExecutor<Learner> {

    Optional<Learner> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
