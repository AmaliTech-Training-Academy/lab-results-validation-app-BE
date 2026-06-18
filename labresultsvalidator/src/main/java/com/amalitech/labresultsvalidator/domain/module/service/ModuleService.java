package com.amalitech.labresultsvalidator.domain.module.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.enums.ModuleStatus;
import com.amalitech.labresultsvalidator.domain.module.dto.CreateModuleRequest;
import com.amalitech.labresultsvalidator.domain.module.dto.ModuleResponse;
import com.amalitech.labresultsvalidator.domain.module.dto.PatchModuleRequest;
import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import com.amalitech.labresultsvalidator.domain.module.repository.ModuleRepository;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.specialization.repository.SpecializationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ModuleService {

    private final ModuleRepository moduleRepository;
    private final SpecializationRepository specializationRepository;
    private final CohortRepository cohortRepository;

    public ModuleResponse createModule(CreateModuleRequest request) {
        Specialization specialization = specializationRepository
                .findByIdAndCohortId(request.getSpecializationId(), request.getCohortId())
                .orElseThrow(() -> new UnprocessableEntityException(
                        "Cohort and specialization combination does not exist"));

        boolean locked = cohortRepository.findIsLockedById(request.getCohortId()).orElse(false);
        if (locked) {
            throw new UnprocessableEntityException(
                    "Cohort is locked and cannot be modified");
        }

        int nextSequence = moduleRepository.countBySpecializationId(specialization.getId()) + 1;

        Module module = Module.builder()
                .specialization(specialization)
                .name(request.getName())
                .sequence(nextSequence)
                .status(ModuleStatus.ACTIVE)
                .build();

        module = moduleRepository.save(module);
        return toResponse(module);
    }

    @Transactional(readOnly = true)
    public List<ModuleResponse> getModules(UUID cohortId, UUID specializationId) {
        List<Module> modules;

        if (specializationId != null && cohortId != null) {
            modules = moduleRepository.findAllBySpecializationIdAndSpecializationCohortId(specializationId, cohortId);
        } else if (specializationId != null) {
            modules = moduleRepository.findAllBySpecializationId(specializationId);
        } else if (cohortId != null) {
            modules = moduleRepository.findAllBySpecializationCohortId(cohortId);
        } else {
            modules = moduleRepository.findAll();
        }

        return modules.stream().map(this::toResponse).toList();
    }

    public ModuleResponse patchModule(UUID id, PatchModuleRequest request) {
        Module module = moduleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Module not found with ID: " + id));

        module.setStatus(request.getStatus());
        module = moduleRepository.save(module);
        return toResponse(module);
    }

    private ModuleResponse toResponse(Module module) {
        Specialization spec = module.getSpecialization();
        return ModuleResponse.builder()
                .id(module.getId())
                .name(module.getName())
                .sequence(module.getSequence())
                .specializationId(spec.getId())
                .specializationName(spec.getName())
                .cohortId(spec.getCohort().getId())
                .cohortName(spec.getCohort().getName())
                .status(module.getStatus())
                .build();
    }
}
