package com.amalitech.labresultsvalidator.domain.instructor.repository;

import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InstructorContactRepository extends JpaRepository<InstructorContact, UUID> {

    Optional<InstructorContact> findByEmailIgnoreCase(String email);

    Optional<InstructorContact> findByFullNameIgnoreCase(String fullName);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);
}
