package com.amalitech.labresultsvalidator.domain.reference.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.reference.dto.CohortReferenceResponse;
import com.amalitech.labresultsvalidator.domain.instructor.dto.InstructorContactResponse;
import com.amalitech.labresultsvalidator.domain.reference.dto.LabResponse;
import com.amalitech.labresultsvalidator.domain.reference.dto.LearnerResponse;
import com.amalitech.labresultsvalidator.domain.reference.dto.ModuleWithLabsResponse;
import com.amalitech.labresultsvalidator.domain.reference.dto.SpecializationWithModulesResponse;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.reference.entity.Lab;
import com.amalitech.labresultsvalidator.domain.reference.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.reference.entity.Learner;
import com.amalitech.labresultsvalidator.domain.reference.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.SpecializationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CohortReferenceQueryService {

    private final CohortRepository cohortRepository;
    private final SpecializationRepository specializationRepository;
    private final LabModuleRepository labModuleRepository;
    private final LabRepository labRepository;
    private final LearnerRepository learnerRepository;
    private final InstructorContactRepository instructorContactRepository;

    public CohortReferenceResponse getCohortReference(UUID cohortId) {
        if (!cohortRepository.existsById(cohortId)) {
            throw new ResourceNotFoundException("Cohort not found with id: " + cohortId);
        }

        List<Specialization> specializations = specializationRepository.findAllByCohortId(cohortId);
        List<UUID> specializationIds = specializations.stream().map(Specialization::getId).toList();

        List<LabModule> modules = labModuleRepository.findAllBySpecializationIdIn(specializationIds);
        List<UUID> moduleIds = modules.stream().map(LabModule::getId).toList();

        Map<UUID, List<Lab>> labsByModuleId = labRepository.findAllByModuleIdIn(moduleIds).stream()
            .collect(Collectors.groupingBy(Lab::getModuleId));

        Map<UUID, List<LabModule>> modulesBySpecializationId = modules.stream()
            .collect(Collectors.groupingBy(LabModule::getSpecializationId));

        List<SpecializationWithModulesResponse> specializationResponses = specializations.stream()
            .map(spec -> toSpecializationResponse(spec, modulesBySpecializationId, labsByModuleId))
            .toList();

        List<LearnerResponse> learnerResponses = learnerRepository.findAllByCohortId(cohortId).stream()
            .map(this::toLearnerResponse)
            .toList();

        List<InstructorContactResponse> instructorResponses = instructorContactRepository.findAll().stream()
            .map(this::toInstructorResponse)
            .toList();

        return CohortReferenceResponse.builder()
            .specializations(specializationResponses)
            .learners(learnerResponses)
            .instructors(instructorResponses)
            .build();
    }

    private SpecializationWithModulesResponse toSpecializationResponse(
        Specialization spec,
        Map<UUID, List<LabModule>> modulesBySpecializationId,
        Map<UUID, List<Lab>> labsByModuleId
    ) {
        List<ModuleWithLabsResponse> moduleResponses = modulesBySpecializationId
            .getOrDefault(spec.getId(), List.of()).stream()
            .sorted(Comparator.comparing(LabModule::getCode))
            .map(module -> toModuleResponse(module, labsByModuleId))
            .toList();

        return SpecializationWithModulesResponse.builder()
            .id(spec.getId())
            .cohortId(spec.getCohortId())
            .name(spec.getName())
            .code(spec.getCode())
            .modules(moduleResponses)
            .build();
    }

    private ModuleWithLabsResponse toModuleResponse(LabModule module, Map<UUID, List<Lab>> labsByModuleId) {
        List<LabResponse> labResponses = labsByModuleId.getOrDefault(module.getId(), List.of()).stream()
            .map(lab -> LabResponse.builder()
                .id(lab.getId())
                .moduleId(lab.getModuleId())
                .title(lab.getTitle())
                .maxScore(lab.getMaxScore())
                .build())
            .toList();

        return ModuleWithLabsResponse.builder()
            .id(module.getId())
            .specializationId(module.getSpecializationId())
            .name(module.getName())
            .code(module.getCode())
            .status(module.getStatus())
            .labs(labResponses)
            .build();
    }

    private LearnerResponse toLearnerResponse(Learner learner) {
        return LearnerResponse.builder()
            .id(learner.getId())
            .fullName(learner.getFullName())
            .email(learner.getEmail())
            .cohortId(learner.getCohortId())
            .specializationId(learner.getSpecializationId())
            .status(learner.getStatus())
            .build();
    }

    private InstructorContactResponse toInstructorResponse(InstructorContact instructor) {
        return InstructorContactResponse.builder()
            .id(instructor.getId())
            .email(instructor.getEmail())
            .fullName(instructor.getFullName())
            .isActive(instructor.isActive())
            .build();
    }
}
