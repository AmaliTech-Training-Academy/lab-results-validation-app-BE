package com.amalitech.labresultsvalidator.domain.user.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.dto.ProvisionInstructorRequest;
import com.amalitech.labresultsvalidator.domain.user.dto.ProvisionInstructorResponse;
import com.amalitech.labresultsvalidator.domain.user.dto.UpdateUserRequest;
import com.amalitech.labresultsvalidator.domain.user.dto.UserResponseDTO;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.event.InstructorProvisionedEvent;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignedModuleResponse;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.entity.UserModuleAssignment;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.repository.UserModuleAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private UserModuleAssignmentRepository assignmentRepository;

    @InjectMocks
    private UserService userService;

    private User buildInstructor(UUID id) {
        return User.builder()
                .id(id)
                .email("instructor@test.com")
                .passwordHash("hash")
                .role(UserRole.INSTRUCTOR)
                .isActive(true)
                .mustChangePassword(true)
                .build();
    }

    // --- provisionInstructor ---

    @Test
    void provisionInstructor_whenEmailAlreadyExists_throwsDuplicateResourceException() {
        ProvisionInstructorRequest request = new ProvisionInstructorRequest();
        ReflectionTestUtils.setField(request, "email", "dup@test.com");
        when(userRepository.findByEmail("dup@test.com")).thenReturn(Optional.of(buildInstructor(UUID.randomUUID())));

        assertThatThrownBy(() -> userService.provisionInstructor(request))
                .isInstanceOf(DuplicateResourceException.class);

        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void provisionInstructor_savesNewInstructor_andPublishesEvent() {
        ProvisionInstructorRequest request = new ProvisionInstructorRequest();
        ReflectionTestUtils.setField(request, "email", "new@test.com");
        UUID savedId = UUID.randomUUID();
        User saved = User.builder()
                .id(savedId)
                .email("new@test.com")
                .passwordHash("encodedPass")
                .role(UserRole.INSTRUCTOR)
                .isActive(true)
                .mustChangePassword(true)
                .build();
        when(userRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPass");
        when(userRepository.save(any(User.class))).thenReturn(saved);

        ProvisionInstructorResponse response = userService.provisionInstructor(request);

        assertThat(response.getId()).isEqualTo(savedId);
        assertThat(response.getEmail()).isEqualTo("new@test.com");

        ArgumentCaptor<InstructorProvisionedEvent> eventCaptor =
                ArgumentCaptor.forClass(InstructorProvisionedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().email()).isEqualTo("new@test.com");
        assertThat(eventCaptor.getValue().temporaryPassword()).isNotBlank();
    }

    @Test
    void provisionInstructor_setsInstructorRoleAndMustChangePassword() {
        ProvisionInstructorRequest request = new ProvisionInstructorRequest();
        ReflectionTestUtils.setField(request, "email", "new@test.com");
        when(userRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        User saved = buildInstructor(UUID.randomUUID());
        when(userRepository.save(userCaptor.capture())).thenReturn(saved);

        userService.provisionInstructor(request);

        User captured = userCaptor.getValue();
        assertThat(captured.getRole()).isEqualTo(UserRole.INSTRUCTOR);
        assertThat(captured.isMustChangePassword()).isTrue();
        assertThat(captured.isActive()).isTrue();
    }

    // --- updateInstructor ---

    @Test
    void updateInstructor_whenNotFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateInstructor(id, new UpdateUserRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateInstructor_whenUserIsNotInstructor_throwsIllegalArgumentException() {
        UUID id = UUID.randomUUID();
        User admin = User.builder()
                .id(id).email("a@test.com").passwordHash("h")
                .role(UserRole.ADMIN).isActive(true).mustChangePassword(false)
                .build();
        when(userRepository.findById(id)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.updateInstructor(id, new UpdateUserRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an instructor");
    }

    @Test
    void updateInstructor_whenDuplicateEmail_throwsDuplicateResourceException() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        User instructor = buildInstructor(id);
        User other = buildInstructor(otherId);
        when(userRepository.findById(id)).thenReturn(Optional.of(instructor));

        UpdateUserRequest request = new UpdateUserRequest();
        ReflectionTestUtils.setField(request, "email", "taken@test.com");
        when(userRepository.findByEmail("taken@test.com")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> userService.updateInstructor(id, request))
                .isInstanceOf(DuplicateResourceException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateInstructor_updatesEmailAndActiveFlag() {
        UUID id = UUID.randomUUID();
        User instructor = buildInstructor(id);
        when(userRepository.findById(id)).thenReturn(Optional.of(instructor));

        UpdateUserRequest request = new UpdateUserRequest();
        ReflectionTestUtils.setField(request, "email", "updated@test.com");
        ReflectionTestUtils.setField(request, "isActive", false);

        when(userRepository.findByEmail("updated@test.com")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenReturn(instructor);
        when(assignmentRepository.findAllByUserId(id)).thenReturn(Collections.emptyList());

        UserResponseDTO response = userService.updateInstructor(id, request);

        assertThat(instructor.getEmail()).isEqualTo("updated@test.com");
        assertThat(instructor.isActive()).isFalse();
        assertThat(response).isNotNull();
    }

    // --- listInstructors ---

    @Test
    void listInstructors_whenNoneExist_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        PagedResponse<UserResponseDTO> result = userService.listInstructors(null, null, null, pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void listInstructors_returnsInstructorsWithAssignedModules() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        User i1 = buildInstructor(id1);
        User i2 = User.builder()
                .id(id2).email("i2@test.com").passwordHash("h")
                .role(UserRole.INSTRUCTOR).isActive(true).mustChangePassword(false).build();

        Pageable pageable = PageRequest.of(0, 20);
        Page<User> page = new PageImpl<>(List.of(i1, i2), pageable, 2);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(assignmentRepository.findAllByUserIdIn(any())).thenReturn(Collections.emptyList());

        PagedResponse<UserResponseDTO> result = userService.listInstructors(null, null, null, pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(UserResponseDTO::getEmail)
                .containsExactlyInAnyOrder("instructor@test.com", "i2@test.com");
    }

    @Test
    void listInstructors_groupsModulesByInstructor() {
        UUID instructorId = UUID.randomUUID();
        User instructor = buildInstructor(instructorId);

        Pageable pageable = PageRequest.of(0, 20);
        Page<User> page = new PageImpl<>(List.of(instructor), pageable, 1);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        com.amalitech.labresultsvalidator.domain.module.entity.Module module =
                com.amalitech.labresultsvalidator.domain.module.entity.Module.builder()
                        .id(UUID.randomUUID())
                        .name("Module A")
                        .specialization(
                                com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization
                                        .builder().id(UUID.randomUUID()).name("Backend").build())
                        .build();

        UserModuleAssignment assignment = UserModuleAssignment.builder()
                .id(UUID.randomUUID())
                .user(instructor)
                .module(module)
                .build();

        when(assignmentRepository.findAllByUserIdIn(any())).thenReturn(List.of(assignment));

        PagedResponse<UserResponseDTO> result = userService.listInstructors(null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getAssignedModules()).hasSize(1);
        assertThat(result.getContent().get(0).getAssignedModules().get(0).getModuleName())
                .isEqualTo("Module A");
    }
}
