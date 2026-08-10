package com.amalitech.labresultsvalidator.domain.instructor.repository;

import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorSpecializationAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InstructorSpecializationAssignmentRepository
    extends JpaRepository<InstructorSpecializationAssignment, UUID> {

    void deleteAllBySpecializationIdIn(List<UUID> specializationIds);

    List<InstructorSpecializationAssignment> findAllBySpecializationIdIn(List<UUID> specializationIds);
}