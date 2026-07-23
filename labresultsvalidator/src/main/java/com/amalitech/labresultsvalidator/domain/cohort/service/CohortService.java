package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.AttachSharePointLinkRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CreateCohortRequest;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CohortService {

    private final CohortRepository cohortRepository;

    public CohortResponse createCohort(CreateCohortRequest request) {
        if (cohortRepository.existsByNameIgnoreCase(request.getName())) {
            throw new DuplicateResourceException("Cohort name must be unique");
        }

        User actor = currentUser();
        Cohort cohort = Cohort.builder()
            .name(request.getName())
            .startDate(request.getStartDate())
            .endDate(request.getEndDate())
            .lifecycleState(CohortLifecycleState.DRAFT)
            .isActive(true)
            .build();
        cohort.setCreatedBy(actor.getId());
        cohort.setUpdatedBy(actor.getId());

        return toResponse(cohortRepository.save(cohort));
    }

    public PagedResponse<CohortResponse> getCohorts(Pageable pageable) {
        Page<CohortResponse> page = cohortRepository.findAll(pageable).map(this::toResponse);
        return PagedResponse.of(page);
    }

    public CohortResponse attachSharePointLink(UUID cohortId, AttachSharePointLinkRequest request) {
        Cohort cohort = cohortRepository.findById(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with ID: " + cohortId));

        if (cohort.getLifecycleState() != CohortLifecycleState.DRAFT) {
            throw new UnprocessableEntityException(
                "A SharePoint link can only be attached to a cohort in DRAFT");
        }

        cohort.setSharepointFolderUrl(request.getFolderUrl());
        cohort.setUpdatedBy(currentUser().getId());

        return toResponse(cohortRepository.save(cohort));
    }

    private CohortResponse toResponse(Cohort cohort) {
        return CohortResponse.builder()
            .id(cohort.getId())
            .name(cohort.getName())
            .startDate(cohort.getStartDate())
            .endDate(cohort.getEndDate())
            .lifecycleState(cohort.getLifecycleState())
            .isActive(cohort.isActive())
            .sharepointFolderUrl(cohort.getSharepointFolderUrl())
            .createdAt(cohort.getCreatedAt())
            .build();
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
