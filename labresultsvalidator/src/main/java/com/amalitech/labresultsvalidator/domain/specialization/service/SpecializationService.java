package com.amalitech.labresultsvalidator.domain.specialization.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.specialization.dto.CreateSpecializationRequest;
import com.amalitech.labresultsvalidator.domain.specialization.dto.SpecializationResponse;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.specialization.repository.SpecializationRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SpecializationService {

    private final SpecializationRepository specializationRepository;
    private final CohortRepository cohortRepository;

    public SpecializationResponse createSpecialization(CreateSpecializationRequest request) {
        User currentUser = (User) SecurityContextHolder
                .getContext().getAuthentication()
                .getPrincipal();

        Cohort cohort = cohortRepository.findById(request.getCohortId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Cohort with id '" + request.getCohortId() + "' not found"));

        if (specializationRepository.existsByCohortIdAndName(request.getCohortId(), request.getName())) {
            throw new DuplicateResourceException(
                    "Specialization with name '" + request.getName() + "' already exists in this cohort");
        }

        if (specializationRepository.existsByCohortIdAndCode(request.getCohortId(), request.getCode())) {
            throw new DuplicateResourceException(
                    "Specialization with code '" + request.getCode() + "' already exists in this cohort");
        }

        Specialization specialization = Specialization.builder()
                .cohort(cohort)
                .name(request.getName())
                .code(request.getCode().toUpperCase())
                .build();
        specialization.setCreatedBy(currentUser.getId());
        specialization.setUpdatedBy(currentUser.getId());

        Specialization saved = specializationRepository.save(specialization);

        return mapToResponse(saved);
    }

    private SpecializationResponse mapToResponse(Specialization specialization) {
        return SpecializationResponse.builder()
                .id(specialization.getId())
                .cohortId(specialization.getCohort().getId())
                .cohortName(specialization.getCohort().getName())
                .name(specialization.getName())
                .code(specialization.getCode())
                .createdAt(specialization.getCreatedAt())
                .updatedAt(specialization.getUpdatedAt())
                .build();
    }
}