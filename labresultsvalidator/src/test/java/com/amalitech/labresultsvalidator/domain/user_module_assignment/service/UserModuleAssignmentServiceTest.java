package com.amalitech.labresultsvalidator.domain.user_module_assignment.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import com.amalitech.labresultsvalidator.domain.module.repository.ModuleRepository;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignModuleRequest;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignModuleResponse;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignedModuleResponse;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.entity.UserModuleAssignment;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.repository.UserModuleAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserModuleAssignmentServiceTest {

    @Mock private UserModuleAssignmentRepository userModuleAssignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private ModuleRepository moduleRepository;

    @InjectMocks
    private UserModuleAssignmentService assignmentService;

    private User adminUser;
    private User instructor;
    private UUID instructorId;
    private Module module;
    private UUID moduleId;

    @BeforeEach
    void setUp() {
        instructorId = UUID.randomUUID();
        moduleId = UUID.randomUUID();

        adminUser = User.builder()
                .id(UUID.randomUUID()).email("admin@test.com").passwordHash("h")
                .role(UserRole.ADMIN).isActive(true).mustChangePassword(false).build();

        instructor = User.builder()
                .id(instructorId).email("instructor@test.com").passwordHash("h")
                .role(UserRole.INSTRUCTOR).isActive(true).mustChangePassword(false).build();

        Specialization spec = Specialization.builder()
                .id(UUID.randomUUID()).name("Backend").build();

        module = Module.builder()
                .id(moduleId).name("Module A").specialization(spec).build();

        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(adminUser, null, adminUser.getAuthorities())
        ));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- assignModule ---

    @Test
    void assignModule_whenInstructorNotFound_throwsResourceNotFoundException() {
        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.empty());

        AssignModuleRequest request = buildRequest(List.of(moduleId));

        assertThatThrownBy(() -> assignmentService.assignModule(instructorId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(instructorId.toString());
    }

    @Test
    void assignModule_whenUserIsNotInstructor_throwsIllegalArgumentException() {
        User admin = User.builder()
                .id(instructorId).email("a@test.com").passwordHash("h")
                .role(UserRole.ADMIN).isActive(true).mustChangePassword(false).build();
        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> assignmentService.assignModule(instructorId, buildRequest(List.of(moduleId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an instructor");
    }

    @Test
    void assignModule_whenModuleNotFound_throwsResourceNotFoundException() {
        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.of(instructor));
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assignmentService.assignModule(instructorId, buildRequest(List.of(moduleId))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(moduleId.toString());

        verify(userModuleAssignmentRepository, never()).saveAll(any());
    }

    @Test
    void assignModule_whenAlreadyAssigned_throwsDuplicateResourceException() {
        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.of(instructor));
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.of(module));
        when(userModuleAssignmentRepository.existsByUserIdAndModuleId(instructorId, moduleId)).thenReturn(true);

        assertThatThrownBy(() -> assignmentService.assignModule(instructorId, buildRequest(List.of(moduleId))))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Module A");

        verify(userModuleAssignmentRepository, never()).saveAll(any());
    }

    @Test
    void assignModule_success_savesAndReturnsResponse() {
        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.of(instructor));
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.of(module));
        when(userModuleAssignmentRepository.existsByUserIdAndModuleId(instructorId, moduleId)).thenReturn(false);

        AssignModuleResponse response = assignmentService.assignModule(instructorId, buildRequest(List.of(moduleId)));

        verify(userModuleAssignmentRepository).saveAll(any());
        assertThat(response.getInstructorId()).isEqualTo(instructorId);
        assertThat(response.getInstructorEmail()).isEqualTo("instructor@test.com");
        assertThat(response.getAssignedModules()).hasSize(1);
        assertThat(response.getAssignedModules().get(0).getModuleName()).isEqualTo("Module A");
    }

    // --- removeModuleAssignments ---

    @Test
    void removeModuleAssignments_whenInstructorNotFound_throwsResourceNotFoundException() {
        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assignmentService.removeModuleAssignments(instructorId, buildRequest(List.of(moduleId))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void removeModuleAssignments_whenNotInstructor_throwsIllegalArgumentException() {
        User notInstructor = User.builder()
                .id(instructorId).email("a@test.com").passwordHash("h")
                .role(UserRole.ADMIN).isActive(true).mustChangePassword(false).build();
        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.of(notInstructor));

        assertThatThrownBy(() -> assignmentService.removeModuleAssignments(instructorId, buildRequest(List.of(moduleId))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeModuleAssignments_whenModuleNotAssigned_throwsResourceNotFoundException() {
        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.of(instructor));
        when(userModuleAssignmentRepository.findAllByUserId(instructorId)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> assignmentService.removeModuleAssignments(instructorId, buildRequest(List.of(moduleId))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(moduleId.toString());
    }

    @Test
    void removeModuleAssignments_success_deletesAndReturnsRemainingModules() {
        UUID otherModuleId = UUID.randomUUID();
        Module otherModule = Module.builder()
                .id(otherModuleId).name("Module B")
                .specialization(module.getSpecialization()).build();

        UserModuleAssignment assignA = UserModuleAssignment.builder()
                .id(UUID.randomUUID()).user(instructor).module(module).build();
        UserModuleAssignment assignB = UserModuleAssignment.builder()
                .id(UUID.randomUUID()).user(instructor).module(otherModule).build();

        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.of(instructor));
        when(userModuleAssignmentRepository.findAllByUserId(instructorId)).thenReturn(List.of(assignA, assignB));

        List<AssignedModuleResponse> remaining =
                assignmentService.removeModuleAssignments(instructorId, buildRequest(List.of(moduleId)));

        verify(userModuleAssignmentRepository).deleteAll(List.of(assignA));
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getModuleName()).isEqualTo("Module B");
    }

    // --- getInstructorModules ---

    @Test
    void getInstructorModules_whenInstructorNotFound_throwsResourceNotFoundException() {
        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assignmentService.getInstructorModules(instructorId, PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getInstructorModules_whenNotInstructor_throwsIllegalArgumentException() {
        User admin = User.builder()
                .id(instructorId).email("a@test.com").passwordHash("h")
                .role(UserRole.ADMIN).isActive(true).mustChangePassword(false).build();
        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> assignmentService.getInstructorModules(instructorId, PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getInstructorModules_success_returnsMappedModules() {
        Pageable pageable = PageRequest.of(0, 20);
        UserModuleAssignment assignment = UserModuleAssignment.builder()
                .id(UUID.randomUUID()).user(instructor).module(module).build();
        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.of(instructor));
        when(userModuleAssignmentRepository.findAllByUserId(eq(instructorId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(assignment)));

        PagedResponse<AssignedModuleResponse> result = assignmentService.getInstructorModules(instructorId, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getModuleId()).isEqualTo(moduleId);
        assertThat(result.getContent().get(0).getModuleName()).isEqualTo("Module A");
        assertThat(result.getContent().get(0).getSpecializationName()).isEqualTo("Backend");
    }

    @Test
    void getInstructorModules_whenNoAssignments_returnsEmptyList() {
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findByIdAndIsActiveTrue(instructorId)).thenReturn(Optional.of(instructor));
        when(userModuleAssignmentRepository.findAllByUserId(eq(instructorId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        PagedResponse<AssignedModuleResponse> result = assignmentService.getInstructorModules(instructorId, pageable);

        assertThat(result.getContent()).isEmpty();
    }

    private AssignModuleRequest buildRequest(List<UUID> moduleIds) {
        AssignModuleRequest request = new AssignModuleRequest();
        ReflectionTestUtils.setField(request, "moduleIds", moduleIds);
        return request;
    }
}
