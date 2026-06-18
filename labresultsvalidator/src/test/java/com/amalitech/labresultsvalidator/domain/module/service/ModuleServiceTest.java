package com.amalitech.labresultsvalidator.domain.module.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.enums.ModuleStatus;
import com.amalitech.labresultsvalidator.domain.module.dto.CreateModuleRequest;
import com.amalitech.labresultsvalidator.domain.module.dto.ModuleResponse;
import com.amalitech.labresultsvalidator.domain.module.dto.PatchModuleRequest;
import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import com.amalitech.labresultsvalidator.domain.module.repository.ModuleRepository;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.specialization.repository.SpecializationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModuleServiceTest {

    @Mock
    private ModuleRepository moduleRepository;

    @Mock
    private SpecializationRepository specializationRepository;

    @Mock
    private CohortRepository cohortRepository;

    @InjectMocks
    private ModuleService moduleService;

    private static final UUID COHORT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SPEC_ID   = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MODULE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private Cohort buildCohort() {
        Cohort cohort = new Cohort();
        ReflectionTestUtils.setField(cohort, "id", COHORT_ID);
        ReflectionTestUtils.setField(cohort, "name", "Cohort 7");
        return cohort;
    }

    private Specialization buildSpecialization() {
        Specialization spec = new Specialization();
        ReflectionTestUtils.setField(spec, "id", SPEC_ID);
        ReflectionTestUtils.setField(spec, "name", "Data Science");
        ReflectionTestUtils.setField(spec, "cohort", buildCohort());
        return spec;
    }

    private Module buildModule() {
        Module module = Module.builder()
                .specialization(buildSpecialization())
                .name("Intro to Python")
                .sequence(1)
                .status(ModuleStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(module, "id", MODULE_ID);
        return module;
    }

    private CreateModuleRequest buildCreateRequest() {
        CreateModuleRequest req = new CreateModuleRequest();
        ReflectionTestUtils.setField(req, "name", "Intro to Python");
        ReflectionTestUtils.setField(req, "cohortId", COHORT_ID);
        ReflectionTestUtils.setField(req, "specializationId", SPEC_ID);
        return req;
    }

    // ─────────────────────── createModule ────────────────────────────────────

    @Test
    void createModule_withValidCohortSpecCombo_returnsPopulatedResponse() {
        when(specializationRepository.findByIdAndCohortId(SPEC_ID, COHORT_ID))
                .thenReturn(Optional.of(buildSpecialization()));
        when(cohortRepository.findIsLockedById(COHORT_ID)).thenReturn(Optional.of(false));
        when(moduleRepository.countBySpecializationId(SPEC_ID)).thenReturn(0);
        when(moduleRepository.save(any(Module.class))).thenReturn(buildModule());

        ModuleResponse response = moduleService.createModule(buildCreateRequest());

        assertThat(response.getName()).isEqualTo("Intro to Python");
        assertThat(response.getSequence()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(ModuleStatus.ACTIVE);
        assertThat(response.getCohortId()).isEqualTo(COHORT_ID);
        assertThat(response.getSpecializationId()).isEqualTo(SPEC_ID);
        assertThat(response.getCohortName()).isEqualTo("Cohort 7");
        assertThat(response.getSpecializationName()).isEqualTo("Data Science");
    }

    @Test
    void createModule_withInvalidCohortSpecCombo_throwsUnprocessableEntity() {
        when(specializationRepository.findByIdAndCohortId(SPEC_ID, COHORT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> moduleService.createModule(buildCreateRequest()))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Cohort and specialization combination does not exist");
    }

    @Test
    void createModule_sequenceIsCountPlusOne() {
        when(specializationRepository.findByIdAndCohortId(SPEC_ID, COHORT_ID))
                .thenReturn(Optional.of(buildSpecialization()));
        when(cohortRepository.findIsLockedById(COHORT_ID)).thenReturn(Optional.of(false));
        when(moduleRepository.countBySpecializationId(SPEC_ID)).thenReturn(4);
        when(moduleRepository.save(any(Module.class))).thenAnswer(inv -> inv.getArgument(0));

        moduleService.createModule(buildCreateRequest());

        verify(moduleRepository).save(argThat(m -> m.getSequence() == 5));
    }

    @Test
    void createModule_whenCohortIsLocked_throwsUnprocessableEntityException() {
        when(specializationRepository.findByIdAndCohortId(SPEC_ID, COHORT_ID))
                .thenReturn(Optional.of(buildSpecialization()));
        when(cohortRepository.findIsLockedById(COHORT_ID)).thenReturn(Optional.of(true));

        assertThatThrownBy(() -> moduleService.createModule(buildCreateRequest()))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("locked");
    }

    // ─────────────────────── getModules ──────────────────────────────────────

    @Test
    void getModules_withBothFilters_queriesBySpecializationAndCohort() {
        Pageable pageable = PageRequest.of(0, 20);
        when(moduleRepository.findAllBySpecializationIdAndSpecializationCohortId(SPEC_ID, COHORT_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(buildModule())));

        PagedResponse<ModuleResponse> result = moduleService.getModules(COHORT_ID, SPEC_ID, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(moduleRepository).findAllBySpecializationIdAndSpecializationCohortId(SPEC_ID, COHORT_ID, pageable);
    }

    @Test
    void getModules_withOnlySpecializationId_queriesBySpecialization() {
        Pageable pageable = PageRequest.of(0, 20);
        when(moduleRepository.findAllBySpecializationId(SPEC_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(buildModule())));

        PagedResponse<ModuleResponse> result = moduleService.getModules(null, SPEC_ID, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(moduleRepository).findAllBySpecializationId(SPEC_ID, pageable);
    }

    @Test
    void getModules_withOnlyCohortId_queriesByCohort() {
        Pageable pageable = PageRequest.of(0, 20);
        when(moduleRepository.findAllBySpecializationCohortId(COHORT_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(buildModule())));

        PagedResponse<ModuleResponse> result = moduleService.getModules(COHORT_ID, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(moduleRepository).findAllBySpecializationCohortId(COHORT_ID, pageable);
    }

    @Test
    void getModules_withNoFilters_returnsAll() {
        Pageable pageable = PageRequest.of(0, 20);
        when(moduleRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(buildModule())));

        PagedResponse<ModuleResponse> result = moduleService.getModules(null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(moduleRepository).findAll(pageable);
    }

    // ─────────────────────── patchModule ─────────────────────────────────────

    @Test
    void patchModule_archivesModule_returnsArchivedStatus() {
        when(moduleRepository.findById(MODULE_ID)).thenReturn(Optional.of(buildModule()));
        when(moduleRepository.save(any(Module.class))).thenAnswer(inv -> inv.getArgument(0));

        PatchModuleRequest req = new PatchModuleRequest();
        ReflectionTestUtils.setField(req, "status", ModuleStatus.ARCHIVED);

        ModuleResponse response = moduleService.patchModule(MODULE_ID, req);

        assertThat(response.getStatus()).isEqualTo(ModuleStatus.ARCHIVED);
        verify(moduleRepository).save(argThat(m -> m.getStatus() == ModuleStatus.ARCHIVED));
    }

    @Test
    void patchModule_reactivatesArchivedModule() {
        Module archivedModule = buildModule();
        archivedModule.setStatus(ModuleStatus.ARCHIVED);

        when(moduleRepository.findById(MODULE_ID)).thenReturn(Optional.of(archivedModule));
        when(moduleRepository.save(any(Module.class))).thenAnswer(inv -> inv.getArgument(0));

        PatchModuleRequest req = new PatchModuleRequest();
        ReflectionTestUtils.setField(req, "status", ModuleStatus.ACTIVE);

        ModuleResponse response = moduleService.patchModule(MODULE_ID, req);

        assertThat(response.getStatus()).isEqualTo(ModuleStatus.ACTIVE);
    }

    @Test
    void patchModule_withNonExistentId_throwsResourceNotFound() {
        when(moduleRepository.findById(MODULE_ID)).thenReturn(Optional.empty());

        PatchModuleRequest req = new PatchModuleRequest();
        ReflectionTestUtils.setField(req, "status", ModuleStatus.ARCHIVED);

        assertThatThrownBy(() -> moduleService.patchModule(MODULE_ID, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(MODULE_ID.toString());
    }
}
