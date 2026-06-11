package com.amalitech.labresultsvalidator.domain.lab.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.lab.dto.CreateLabRequest;
import com.amalitech.labresultsvalidator.domain.lab.dto.LabResponse;
import com.amalitech.labresultsvalidator.domain.lab.dto.PatchLabRequest;
import com.amalitech.labresultsvalidator.domain.lab.entity.Lab;
import com.amalitech.labresultsvalidator.domain.lab.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import com.amalitech.labresultsvalidator.domain.module.repository.ModuleRepository;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabServiceTest {

    @Mock private LabRepository labRepository;
    @Mock private ModuleRepository moduleRepository;

    @InjectMocks
    private LabService labService;

    private User currentUser;
    private Module module;

    @BeforeEach
    void setUp() {
        currentUser = User.builder()
                .id(UUID.randomUUID())
                .email("admin@test.com")
                .passwordHash("hash")
                .role(UserRole.ADMIN)
                .build();

        Specialization spec = Specialization.builder()
                .id(UUID.randomUUID())
                .name("Backend")
                .build();

        module = Module.builder()
                .id(UUID.randomUUID())
                .name("Module A")
                .specialization(spec)
                .build();

        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(currentUser, null, currentUser.getAuthorities())
        ));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- createLab ---

    @Test
    void createLab_whenModuleNotFound_throwsResourceNotFoundException() {
        CreateLabRequest request = buildCreateRequest(module.getId(), "Lab 1", BigDecimal.TEN);
        when(moduleRepository.findById(module.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> labService.createLab(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(module.getId().toString());

        verify(labRepository, never()).save(any());
    }

    @Test
    void createLab_whenDuplicateTitleInModule_throwsDuplicateResourceException() {
        CreateLabRequest request = buildCreateRequest(module.getId(), "Lab 1", BigDecimal.TEN);
        when(moduleRepository.findById(module.getId())).thenReturn(Optional.of(module));
        when(labRepository.existsByModuleIdAndTitle(module.getId(), "Lab 1")).thenReturn(true);

        assertThatThrownBy(() -> labService.createLab(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Lab 1");

        verify(labRepository, never()).save(any());
    }

    @Test
    void createLab_success_savesAndReturnsMappedResponse() {
        CreateLabRequest request = buildCreateRequest(module.getId(), "Lab 1", new BigDecimal("100.00"));
        when(moduleRepository.findById(module.getId())).thenReturn(Optional.of(module));
        when(labRepository.existsByModuleIdAndTitle(module.getId(), "Lab 1")).thenReturn(false);

        UUID labId = UUID.randomUUID();
        Lab savedLab = Lab.builder()
                .id(labId)
                .module(module)
                .title("Lab 1")
                .maxScore(new BigDecimal("100.00"))
                .build();
        when(labRepository.save(any(Lab.class))).thenReturn(savedLab);

        LabResponse response = labService.createLab(request);

        assertThat(response.getId()).isEqualTo(labId);
        assertThat(response.getTitle()).isEqualTo("Lab 1");
        assertThat(response.getModuleId()).isEqualTo(module.getId());
        assertThat(response.getModuleName()).isEqualTo("Module A");
        assertThat(response.getMaxScore()).isEqualByComparingTo("100.00");
        assertThat(response.isImmutable()).isFalse();
    }

    // --- patchLab ---

    @Test
    void patchLab_whenNotFound_throwsResourceNotFoundException() {
        UUID labId = UUID.randomUUID();
        when(labRepository.findById(labId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> labService.patchLab(labId, new PatchLabRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void patchLab_whenLabIsImmutable_throwsUnprocessableEntityException() {
        UUID labId = UUID.randomUUID();
        Lab immutableLab = Lab.builder()
                .id(labId).module(module).title("Lab 1")
                .maxScore(BigDecimal.TEN).immutable(true).build();
        when(labRepository.findById(labId)).thenReturn(Optional.of(immutableLab));

        assertThatThrownBy(() -> labService.patchLab(labId, new PatchLabRequest()))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void patchLab_whenNewTitleDuplicatesAnother_throwsDuplicateResourceException() {
        UUID labId = UUID.randomUUID();
        Lab lab = Lab.builder()
                .id(labId).module(module).title("Old Title")
                .maxScore(BigDecimal.TEN).immutable(false).build();
        when(labRepository.findById(labId)).thenReturn(Optional.of(lab));

        PatchLabRequest request = new PatchLabRequest();
        ReflectionTestUtils.setField(request, "title", "New Title");
        when(labRepository.existsByModuleIdAndTitle(module.getId(), "New Title")).thenReturn(true);

        assertThatThrownBy(() -> labService.patchLab(labId, request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("New Title");
    }

    @Test
    void patchLab_whenTitleUnchanged_doesNotCheckForDuplicate() {
        UUID labId = UUID.randomUUID();
        Lab lab = Lab.builder()
                .id(labId).module(module).title("Same Title")
                .maxScore(BigDecimal.TEN).immutable(false).build();
        when(labRepository.findById(labId)).thenReturn(Optional.of(lab));
        when(labRepository.save(any())).thenReturn(lab);

        PatchLabRequest request = new PatchLabRequest();
        ReflectionTestUtils.setField(request, "title", "Same Title");

        labService.patchLab(labId, request);

        verify(labRepository, never()).existsByModuleIdAndTitle(any(), any());
    }

    @Test
    void patchLab_updatesAllFields() {
        UUID labId = UUID.randomUUID();
        Lab lab = Lab.builder()
                .id(labId).module(module).title("Old")
                .maxScore(new BigDecimal("50.00")).immutable(false).build();
        when(labRepository.findById(labId)).thenReturn(Optional.of(lab));
        when(labRepository.existsByModuleIdAndTitle(module.getId(), "New")).thenReturn(false);
        when(labRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatchLabRequest request = new PatchLabRequest();
        ReflectionTestUtils.setField(request, "title", "New");
        ReflectionTestUtils.setField(request, "maxScore", new BigDecimal("99.00"));
        ReflectionTestUtils.setField(request, "immutable", true);

        LabResponse response = labService.patchLab(labId, request);

        assertThat(response.getTitle()).isEqualTo("New");
        assertThat(response.getMaxScore()).isEqualByComparingTo("99.00");
        assertThat(response.isImmutable()).isTrue();
    }

    // --- listLabs ---

    @Test
    void listLabs_withModuleId_delegatesToFilteredQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        Lab lab = Lab.builder()
                .id(UUID.randomUUID()).module(module).title("Lab 1")
                .maxScore(BigDecimal.TEN).build();
        when(labRepository.findAllByModuleId(module.getId(), pageable))
                .thenReturn(new PageImpl<>(List.of(lab)));

        Page<LabResponse> result = labService.listLabs(module.getId(), pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("Lab 1");
    }

    @Test
    void listLabs_withoutModuleId_returnsAllLabsSortedByTitle() {
        Pageable pageable = PageRequest.of(0, 10);
        Lab lab = Lab.builder()
                .id(UUID.randomUUID()).module(module).title("Alpha")
                .maxScore(BigDecimal.TEN).build();
        when(labRepository.findAllByOrderByTitleAsc(pageable))
                .thenReturn(new PageImpl<>(List.of(lab)));

        Page<LabResponse> result = labService.listLabs(null, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("Alpha");
    }

    private CreateLabRequest buildCreateRequest(UUID moduleId, String title, BigDecimal maxScore) {
        CreateLabRequest request = new CreateLabRequest();
        ReflectionTestUtils.setField(request, "moduleId", moduleId);
        ReflectionTestUtils.setField(request, "title", title);
        ReflectionTestUtils.setField(request, "maxScore", maxScore);
        return request;
    }
}
