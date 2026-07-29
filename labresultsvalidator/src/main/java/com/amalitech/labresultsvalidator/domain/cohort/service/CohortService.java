package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CohortService {

    private final CohortRepository cohortRepository;
    private final StandupPipelineService standupPipelineService;
    private final ReferenceCommitService referenceCommitService;
    private final AuditEventService auditEventService;

    @Transactional
    public CohortResponse createCohort(CreateCohortRequest req) {
        if (cohortRepository.existsByNameIgnoreCase(req.getName())) {
            throw new DuplicateResourceException("Cohort name must be unique");
        }
        UUID actorId = currentUserId();
        Cohort cohort = Cohort.builder()
            .name(req.getName())
            .startDate(req.getStartDate())
            .endDate(req.getEndDate())
            .build();
        cohort.setCreatedBy(actorId);
        cohort.setUpdatedBy(actorId);
        return toCohortResponse(cohortRepository.save(cohort));
    }

    public Page<CohortResponse> getCohorts(Pageable pageable) {
        return cohortRepository.findAll(pageable).map(this::toCohortResponse);
    }

    @Transactional
    public CohortResponse attachSharePointLink(UUID cohortId, AttachSharePointLinkRequest req) {
        Cohort cohort = cohortRepository.findById(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with ID: " + cohortId));
        if (cohort.getLifecycleState() != CohortLifecycleState.DRAFT) {
            throw new UnprocessableEntityException(
                "A SharePoint link can only be attached to a cohort in DRAFT");
        }
        UUID actorId = currentUserId();
        cohort.setSharepointFolderUrl(req.getFolderUrl());
        cohort.setUpdatedBy(actorId);
        return toCohortResponse(cohortRepository.save(cohort));
    }

    public void acceptReference(UUID cohortId) {
        referenceCommitService.acceptAndCommit(cohortId, currentUserId());
    }

    public void discardReference(UUID cohortId) {
        referenceCommitService.discardAndReset(cohortId, currentUserId());
    }

    @Transactional
    public CohortResponse lockCohort(UUID cohortId) {
        Cohort cohort = cohortRepository.findById(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with ID: " + cohortId));
        if (cohort.getLifecycleState() != CohortLifecycleState.STOOD_UP) {
            throw new UnprocessableEntityException("Only a STOOD_UP cohort can be locked.");
        }
        if (cohort.isLocked()) {
            throw new UnprocessableEntityException("Cohort is already locked.");
        }
        UUID actorId = currentUserId();
        cohort.setLocked(true);
        cohort.setUpdatedBy(actorId);
        CohortResponse response = toCohortResponse(cohortRepository.save(cohort));
        auditEventService.record("COHORT_LOCKED", cohortId, actorId, Map.of("cohortName", cohort.getName()));
        return response;
    }

    @Transactional
    public CohortResponse unlockCohort(UUID cohortId) {
        Cohort cohort = cohortRepository.findById(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with ID: " + cohortId));
        if (!cohort.isLocked()) {
            throw new UnprocessableEntityException("Cohort is not locked.");
        }
        UUID actorId = currentUserId();
        cohort.setLocked(false);
        cohort.setUpdatedBy(actorId);
        CohortResponse response = toCohortResponse(cohortRepository.save(cohort));
        auditEventService.record("COHORT_UNLOCKED", cohortId, actorId, Map.of("cohortName", cohort.getName()));
        return response;
    }

    public CohortResponse getCohort(UUID cohortId) {
        Cohort cohort = cohortRepository.findById(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with id: " + cohortId));
        return toCohortResponse(cohort);
    }

    private UUID currentUserId() {
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return user.getId();
    }

    private CohortResponse toCohortResponse(Cohort cohort) {
        return CohortResponse.builder()
            .id(cohort.getId())
            .name(cohort.getName())
            .startDate(cohort.getStartDate())
            .endDate(cohort.getEndDate())
            .lifecycleState(cohort.getLifecycleState())
            .isLocked(cohort.isLocked())
            .isActive(cohort.isActive())
            .sharepointFolderUrl(cohort.getSharepointFolderUrl())
            .createdAt(cohort.getCreatedAt())
            .build();
    }
}
