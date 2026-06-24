package com.amalitech.labresultsvalidator.domain.learner.repository;

import com.amalitech.labresultsvalidator.domain.learner.entity.Learner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface LearnerRepository
        extends JpaRepository<Learner, UUID>, JpaSpecificationExecutor<Learner> {

    Optional<Learner> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    @Query("SELECT u.email FROM User u WHERE u.email IN :inFileEmails")
    Set<String> findExistingEmails(@Param("inFileEmails") Set<String> inFileEmails);

    @Query("SELECT l FROM Learner l WHERE l.cohort.id = :cohortId AND l.specialization.id = :specializationId")
    List<Learner> findAllByCohortIdAndSpecializationId(
            @Param("cohortId") UUID cohortId,
            @Param("specializationId") UUID specializationId);

    @Query("SELECT l FROM Learner l WHERE l.cohort.id IN :cohortIds")
    List<Learner> findAllByCohortIdIn(@Param("cohortIds") Collection<UUID> cohortIds);
}
