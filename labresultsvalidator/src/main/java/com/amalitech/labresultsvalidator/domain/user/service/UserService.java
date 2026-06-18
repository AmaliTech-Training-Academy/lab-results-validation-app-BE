package com.amalitech.labresultsvalidator.domain.user.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.common.utils.PasswordGenerator;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.dto.ProvisionInstructorRequest;
import com.amalitech.labresultsvalidator.domain.user.dto.ProvisionInstructorResponse;
import com.amalitech.labresultsvalidator.domain.user.dto.UpdateUserRequest;
import com.amalitech.labresultsvalidator.domain.user.dto.UserResponseDTO;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.event.InstructorProvisionedEvent;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignedModuleResponse;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.repository.UserModuleAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final UserModuleAssignmentRepository assignmentRepository;

    @Transactional
    public ProvisionInstructorResponse provisionInstructor(ProvisionInstructorRequest request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new DuplicateResourceException(
                "An account with email " + request.getEmail() + " already exists"
            );
        }

        String rawPassword = PasswordGenerator.generate();

        User instructor = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(UserRole.INSTRUCTOR)
                .isActive(true)
                .mustChangePassword(true)
                .build();

        User saved = userRepository.save(instructor);
        eventPublisher.publishEvent(new InstructorProvisionedEvent(saved.getEmail(), rawPassword));

        return ProvisionInstructorResponse.builder()
                .id(saved.getId())
                .email(saved.getEmail())
                .build();
    }

    public UserResponseDTO updateInstructor(UUID instructorId, UpdateUserRequest request) {
        User instructor = userRepository.findById(instructorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Instructor not found with ID: " + instructorId));

        if (instructor.getRole() != UserRole.INSTRUCTOR) {
            throw new IllegalArgumentException(
                    "User with ID " + instructorId + " is not an instructor");
        }

        if (request.getEmail() != null) {
            if (userRepository.findByEmail(request.getEmail())
                    .filter(u -> !u.getId().equals(instructorId))
                    .isPresent()) {
                throw new DuplicateResourceException(
                        "An account with email " + request.getEmail() + " already exists");
            }
            instructor.setEmail(request.getEmail());
        }

        if (request.getIsActive() != null) {
            instructor.setActive(request.getIsActive());
        }

        User saved = userRepository.save(instructor);

        List<AssignedModuleResponse> assignedModules = assignmentRepository
                .findAllByUserId(saved.getId())
                .stream()
                .map(a -> AssignedModuleResponse.builder()
                        .moduleId(a.getModule().getId())
                        .moduleName(a.getModule().getName())
                        .specializationName(a.getModule().getSpecialization().getName())
                        .build())
                .toList();

        return UserResponseDTO.builder()
                .id(saved.getId())
                .email(saved.getEmail())
                .active(saved.isActive())
                .assignedModules(assignedModules)
                .build();
    }

    public PagedResponse<UserResponseDTO> listInstructors(Pageable pageable) {
        Page<User> userPage = userRepository.findAllByRole(UserRole.INSTRUCTOR, pageable);

        List<UUID> ids = userPage.getContent().stream().map(User::getId).toList();

        Map<UUID, List<AssignedModuleResponse>> modulesByUser = ids.isEmpty()
            ? Collections.emptyMap()
            : assignmentRepository.findAllByUserIdIn(ids).stream()
                .collect(Collectors.groupingBy(
                    a -> a.getUser().getId(),
                    Collectors.mapping(
                        a -> AssignedModuleResponse.builder()
                            .moduleId(a.getModule().getId())
                            .moduleName(a.getModule().getName())
                            .specializationName(a.getModule().getSpecialization().getName())
                            .build(),
                        Collectors.toList()
                    )
                ));

        Page<UserResponseDTO> dtoPage = userPage.map(u -> UserResponseDTO.builder()
            .id(u.getId())
            .email(u.getEmail())
            .active(u.isActive())
            .assignedModules(modulesByUser.getOrDefault(u.getId(), Collections.emptyList()))
            .build());

        return PagedResponse.of(dtoPage);
    }
}
