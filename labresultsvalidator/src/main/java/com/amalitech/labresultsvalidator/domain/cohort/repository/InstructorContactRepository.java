package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.InstructorContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InstructorContactRepository extends JpaRepository<InstructorContact, UUID> {

    Optional<InstructorContact> findByInstructorId(String instructorId);

    Optional<InstructorContact> findByEmailIgnoreCase(String email);

    Optional<InstructorContact> findByFullNameIgnoreCase(String fullName);

    boolean existsByInstructorId(String instructorId);

    boolean existsByEmail(String email);
}
