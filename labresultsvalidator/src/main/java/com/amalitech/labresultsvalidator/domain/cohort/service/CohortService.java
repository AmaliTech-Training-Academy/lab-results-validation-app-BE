package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CreateCohortRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.UpdateCohortRequest;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CohortService {
    private final CohortRepository cohortRepository;

    private User currentUser() {
        return (User) SecurityContextHolder
                .getContext().getAuthentication()
                .getPrincipal();
    }

    public CohortResponse createCohort(CreateCohortRequest request) {
        User currentUser = currentUser();

        if (cohortRepository.existsByName(request.getName())) {
            throw new DuplicateResourceException(
                    "Cohort with name '" + request.getName() + "' already exists");
        }

        if (!request.getEndDate().isAfter(request.getStartDate())) {
            throw new IllegalArgumentException("End date must be after start date");
        }

        Cohort cohort = Cohort.builder()
                .name(request.getName())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .active(true)
                .build();
        cohort.setCreatedBy(currentUser.getId());
        cohort.setUpdatedBy(currentUser.getId());

        Cohort saved = cohortRepository.save(cohort);

        return mapToResponse(saved);
    }

    public PagedResponse<CohortResponse> getCohorts(Pageable pageable) {
        Page<CohortResponse> page = cohortRepository.findAll(pageable)
                .map(this::mapToResponse);
        return PagedResponse.of(page);
    }

    @Transactional
    public CohortResponse updateCohort(UUID id, UpdateCohortRequest request) {
        Cohort cohort = cohortRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Cohort with id '" + id + "' not found"));

        if (request.getName() != null) {
            String newName = request.getName().strip();
            if (!cohort.getName().equalsIgnoreCase(newName)
                    && cohortRepository.existsByNameAndIdNot(newName, id)) {
                throw new DuplicateResourceException(
                        "Cohort with name '" + newName + "' already exists");
            }
            cohort.setName(newName);
        }

        LocalDate effectiveStart = request.getStartDate() != null ? request.getStartDate() : cohort.getStartDate();
        LocalDate effectiveEnd   = request.getEndDate()   != null ? request.getEndDate()   : cohort.getEndDate();

        if (!effectiveEnd.isAfter(effectiveStart)) {
            throw new IllegalArgumentException("End date must be after start date");
        }

        if (request.getStartDate() != null) cohort.setStartDate(request.getStartDate());
        if (request.getEndDate()   != null) cohort.setEndDate(request.getEndDate());
        if (request.getActive()    != null) cohort.setActive(request.getActive());

        cohort.setUpdatedBy(currentUser().getId());

        return mapToResponse(cohortRepository.save(cohort));
    }

    public void deleteCohort(UUID id) {
        Cohort cohort = cohortRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Cohort with id '" + id + "' not found"));

        if (cohortRepository.hasActiveModules(cohort.getId())) {
            throw new UnprocessableEntityException(
                    "Cannot delete cohort '" + cohort.getName() + "' — it has active modules");
        }

        cohortRepository.delete(cohort);
    }

    @Transactional
    public CohortResponse lockCohort(UUID id) {
        Cohort cohort = cohortRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Cohort with id '" + id + "' not found"));
        if (cohort.isLocked()) {
            throw new UnprocessableEntityException("Cohort is already locked");
        }
        cohort.setLocked(true);
        cohort.setUpdatedBy(currentUser().getId());
        return mapToResponse(cohortRepository.save(cohort));
    }

    @Transactional
    public CohortResponse unlockCohort(UUID id) {
        Cohort cohort = cohortRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Cohort with id '" + id + "' not found"));
        if (!cohort.isLocked()) {
            throw new UnprocessableEntityException("Cohort is already unlocked");
        }
        cohort.setLocked(false);
        cohort.setUpdatedBy(currentUser().getId());
        return mapToResponse(cohortRepository.save(cohort));
    }

    private CohortResponse mapToResponse(Cohort cohort) {
        return CohortResponse.builder()
                .id(cohort.getId())
                .name(cohort.getName())
                .startDate(cohort.getStartDate())
                .endDate(cohort.getEndDate())
                .active(cohort.isActive())
                .locked(cohort.isLocked())
                .createdAt(cohort.getCreatedAt())
                .updatedAt(cohort.getUpdatedAt())
                .build();
    }


}
