package com.amalitech.labresultsvalidator.domain.user_module_assignment.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import com.amalitech.labresultsvalidator.domain.module.repository.ModuleRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignModuleRequest;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignModuleResponse;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignedModuleResponse;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.entity.UserModuleAssignment;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.repository.UserModuleAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserModuleAssignmentService {
    private final UserModuleAssignmentRepository userModuleAssignmentRepository;
    private final UserRepository userRepository;
    private final ModuleRepository moduleRepository;

    public AssignModuleResponse assignModule(UUID instructorId, AssignModuleRequest assignModuleRequest) {
        // Step 1 — Find instructor, 404 if not found or inactive
        User instructor = userRepository.findByIdAndIsActiveTrue(instructorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Instructor not found with ID: " + instructorId));

        // Step 2 — Confirm the user is an instructor
        if (instructor.getRole() != UserRole.INSTRUCTOR) {
            throw new IllegalArgumentException(
                    "User with ID " + instructorId + " is not an instructor");
        }

        // Resolve the authenticated user making the assignment
        User currentUser = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        // Step 3 — Process each module ID
        List<UserModuleAssignment> assignments = new ArrayList<>();

        for (UUID moduleId : assignModuleRequest.getModuleIds()) {

            // Check module exists — 404 if not
            Module module = moduleRepository.findById(moduleId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Module not found with ID: " + moduleId));

            // Check not already assigned — 409 if duplicate
            if (userModuleAssignmentRepository.existsByUserIdAndModuleId(instructorId, moduleId)) {
                throw new DuplicateResourceException(
                        "Instructor is already assigned to module: " + module.getName());
            }

            assignments.add(UserModuleAssignment.builder()
                    .user(instructor)
                    .module(module)
                    .createdBy(currentUser.getId())
                    .build());
        }

        // Step 4 — Save all assignments
        userModuleAssignmentRepository.saveAll(assignments);

        // Step 5 — Build response
        List<AssignedModuleResponse> assignedModules = assignments.stream()
                .map(a -> AssignedModuleResponse.builder()
                        .moduleId(a.getModule().getId())
                        .moduleName(a.getModule().getName())
                        .specializationName(a.getModule().getSpecialization().getName())
                        .build())
                .toList();

        return AssignModuleResponse.builder()
                .instructorId(instructor.getId())
                .instructorEmail(instructor.getEmail())
                .assignedModules(assignedModules)
                .build();
    }

    public List<AssignedModuleResponse> getInstructorModules(UUID instructorId) {
        User instructor = userRepository.findByIdAndIsActiveTrue(instructorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Instructor not found with ID: " + instructorId));

        if (instructor.getRole() != UserRole.INSTRUCTOR) {
            throw new IllegalArgumentException(
                    "User with ID " + instructorId + " is not an instructor");
        }

        return userModuleAssignmentRepository
                .findAllByUserId(instructorId)
                .stream()
                .map(a -> AssignedModuleResponse.builder()
                        .moduleId(a.getModule().getId())
                        .moduleName(a.getModule().getName())
                        .specializationName(a.getModule().getSpecialization().getName())
                        .build())
                .toList();
    }
}