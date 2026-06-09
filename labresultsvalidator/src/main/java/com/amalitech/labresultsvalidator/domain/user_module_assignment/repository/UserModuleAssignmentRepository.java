package com.amalitech.labresultsvalidator.domain.user_module_assignment.repository;

import com.amalitech.labresultsvalidator.domain.user_module_assignment.entity.UserModuleAssignment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserModuleAssignmentRepository extends JpaRepository<UserModuleAssignment, UUID> {
    boolean existsByUserIdAndModuleId(UUID userId, UUID moduleId);

    @EntityGraph(attributePaths = {"module", "module.specialization"})
    List<UserModuleAssignment> findAllByUserId(UUID userId);

    @EntityGraph(attributePaths = {"user", "module", "module.specialization"})
    List<UserModuleAssignment> findAllByUserIdIn(List<UUID> userIds);
}
