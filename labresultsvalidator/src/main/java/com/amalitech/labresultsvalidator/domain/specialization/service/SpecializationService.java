package com.amalitech.labresultsvalidator.domain.specialization.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.specialization.dto.CreateSpecializationRequest;
import com.amalitech.labresultsvalidator.domain.specialization.dto.SpecializationResponse;
import com.amalitech.labresultsvalidator.domain.specialization.dto.UpdateSpecializationRequest;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.specialization.repository.SpecializationRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

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

        if (cohort.isLocked()) {
            throw new UnprocessableEntityException(
                    "Cohort '" + cohort.getName() + "' is locked and cannot be modified");
        }

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

    @Transactional
    public SpecializationResponse updateSpecialization(UUID id, UpdateSpecializationRequest request) {
        Specialization specialization = specializationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Specialization with id '" + id + "' not found"));

        if (specialization.getCohort().isLocked()) {
            throw new UnprocessableEntityException(
                    "Cohort '" + specialization.getCohort().getName() + "' is locked and cannot be modified");
        }

        String newName = request.getName();
        String newCode = request.getCode().toUpperCase();
        UUID cohortId = specialization.getCohort().getId();

        if (!specialization.getName().equalsIgnoreCase(newName)
                && specializationRepository.existsByCohortIdAndNameAndIdNot(cohortId, newName, id)) {
            throw new DuplicateResourceException(
                    "Specialization with name '" + newName + "' already exists in this cohort");
        }

        if (!specialization.getCode().equalsIgnoreCase(newCode)
                && specializationRepository.existsByCohortIdAndCodeAndIdNot(cohortId, newCode, id)) {
            throw new DuplicateResourceException(
                    "Specialization with code '" + newCode + "' already exists in this cohort");
        }

        specialization.setName(newName);
        specialization.setCode(newCode);
        specialization.setUpdatedBy(currentUser().getId());

        return mapToResponse(specializationRepository.save(specialization));
    }

    public Page<SpecializationResponse> listSpecializations(UUID cohortId, Pageable pageable) {
        if (cohortId != null) {
            return specializationRepository.findAllByCohortIdOrderByNameAsc(cohortId, pageable)
                    .map(this::mapToResponse);
        }
        return specializationRepository.findAllByOrderByNameAsc(pageable)
                .map(this::mapToResponse);
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private SpecializationResponse mapToResponse(Specialization specialization) {
        return SpecializationResponse.builder()
                .id(specialization.getId())
                .cohortId(specialization.getCohort().getId())
                .name(specialization.getName())
                .code(specialization.getCode())
                .createdAt(specialization.getCreatedAt())
                .updatedAt(specialization.getUpdatedAt())
                .build();
    }
}