package com.amalitech.labresultsvalidator.domain.user.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.service.EmailService;
import com.amalitech.labresultsvalidator.common.utils.PasswordGenerator;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.dto.ProvisionInstructorRequest;
import com.amalitech.labresultsvalidator.domain.user.dto.ProvisionInstructorResponse;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

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
}
