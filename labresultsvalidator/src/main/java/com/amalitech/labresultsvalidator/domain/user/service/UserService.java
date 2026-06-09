package com.amalitech.labresultsvalidator.domain.user.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.service.EmailService;
import com.amalitech.labresultsvalidator.common.utils.PasswordGenerator;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.dto.ProvisionInstructorRequest;
import com.amalitech.labresultsvalidator.domain.user.dto.ProvisionInstructorResponse;
import com.amalitech.labresultsvalidator.domain.user.dto.UserResponseDTO;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignedModuleResponse;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.repository.UserModuleAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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
    private final EmailService emailService;
    private final UserModuleAssignmentRepository assignmentRepository;

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

        emailService.sendInstructorWelcome(saved.getEmail(), rawPassword);

        return ProvisionInstructorResponse.builder()
                .id(saved.getId())
                .email(saved.getEmail())
                .build();
    }

    public List<UserResponseDTO> listInstructors() {
        List<User> instructors = userRepository.findAllByRole(UserRole.INSTRUCTOR);

        if (instructors.isEmpty()) {
            return Collections.emptyList();
        }

        List<UUID> ids = instructors.stream().map(User::getId).toList();

        Map<UUID, List<AssignedModuleResponse>> modulesByUser =
            assignmentRepository.findAllByUserIdIn(ids).stream()
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

        return instructors.stream()
            .map(u -> UserResponseDTO.builder()
                .email(u.getEmail())
                .active(u.isActive())
                .assignedModules(modulesByUser.getOrDefault(u.getId(), Collections.emptyList()))
                .build())
            .toList();
    }
}
