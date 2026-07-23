package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CreateCohortRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.Gate4ResultDto;
import com.amalitech.labresultsvalidator.domain.cohort.dto.GateStateDto;
import com.amalitech.labresultsvalidator.domain.cohort.dto.SetSharePointLinkRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.StandupResultDto;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.gate.Gate4Result;
import com.amalitech.labresultsvalidator.domain.cohort.gate.Gate4ScoreSheetValidator;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CohortService {

    private final CohortRepository cohortRepository;
    private final StandupPipelineService standupPipelineService;
    private final ReferenceCommitService referenceCommitService;
    private final Gate4ScoreSheetValidator gate4;
    private final GraphDriveService graphDriveService;
    private final SharePointProperties sharePointProperties;
    private final AuditEventService auditEventService;

    public CohortService(
        CohortRepository cohortRepository,
        StandupPipelineService standupPipelineService,
        ReferenceCommitService referenceCommitService,
        Gate4ScoreSheetValidator gate4,
        GraphDriveService graphDriveService,
        SharePointProperties sharePointProperties,
        AuditEventService auditEventService
    ) {
        this.cohortRepository = cohortRepository;
        this.standupPipelineService = standupPipelineService;
        this.referenceCommitService = referenceCommitService;
        this.gate4 = gate4;
        this.graphDriveService = graphDriveService;
        this.sharePointProperties = sharePointProperties;
        this.auditEventService = auditEventService;
    }

    @Transactional
    public CohortResponse createCohort(CreateCohortRequest req, UUID actorUserId) {
        if (cohortRepository.existsByNameIgnoreCase(req.name())) {
            throw new DuplicateResourceException("A cohort with the name '" + req.name() + "' already exists.");
        }
        Cohort cohort = Cohort.builder()
            .name(req.name())
            .startDate(req.startDate())
            .endDate(req.endDate())
            .build();
        cohort.setCreatedBy(actorUserId);
        cohort.setUpdatedBy(actorUserId);
        Cohort saved = cohortRepository.save(cohort);
        return toCohortResponse(saved);
    }

    @Transactional
    public CohortResponse setSharePointLink(UUID cohortId, SetSharePointLinkRequest req, UUID actorUserId) {
        Cohort cohort = loadActiveCohort(cohortId);
        if (!"DRAFT".equals(cohort.getLifecycleState())) {
            throw new UnprocessableEntityException("SharePoint link can only be set when cohort is in DRAFT state.");
        }
        cohort.setSharepointFolderUrl(req.sharepointFolderUrl());
        cohort.setUpdatedBy(actorUserId);
        Cohort saved = cohortRepository.save(cohort);
        auditEventService.record("LINK_SUBMITTED", cohortId, actorUserId,
            Map.of("sharepointFolderUrl", req.sharepointFolderUrl()));
        return toCohortResponse(saved);
    }

    public StandupResultDto runStandup(UUID cohortId, UUID actorUserId) {
        return standupPipelineService.runGates123(cohortId, actorUserId);
    }

    public void acceptReference(UUID cohortId, UUID actorUserId) {
        referenceCommitService.acceptAndCommit(cohortId, actorUserId);
    }

    @Transactional
    public Gate4ResultDto runGate4(UUID cohortId, UUID actorUserId) {
        Cohort cohort = loadActiveCohort(cohortId);
        if (!"REFERENCE_ACCEPTED".equals(cohort.getLifecycleState())) {
            throw new UnprocessableEntityException(
                "Cohort must be in REFERENCE_ACCEPTED state to run Gate 4.");
        }

        String driveId = cohort.getSharepointDriveId();
        String itemId = cohort.getSharepointItemId();

        if (driveId == null || itemId == null) {
            throw new UnprocessableEntityException(
                "Cohort is missing SharePoint drive reference. Re-run stand-up.");
        }

        List<DriveItemInfo> children;
        try {
            children = graphDriveService.listChildren(driveId, itemId);
        } catch (GraphAccessException ex) {
            throw new UnprocessableEntityException(
                "Cannot access the cohort SharePoint folder: " + ex.getMessage());
        }

        String scoresFolderName = sharePointProperties.scoresFolder();
        String scoresFolderItemId = children.stream()
            .filter(c -> c.isFolder() && scoresFolderName.equals(c.name()))
            .map(DriveItemInfo::itemId)
            .findFirst()
            .orElseThrow(() -> new UnprocessableEntityException(
                "Scores folder '" + scoresFolderName + "' not found under the cohort folder."));

        Gate4Result result = gate4.validate(driveId, scoresFolderItemId, cohortId);

        if (result.gate().passed()) {
            cohort.setLifecycleState("STOOD_UP");
            cohort.setUpdatedBy(actorUserId);
            cohortRepository.save(cohort);
            auditEventService.record("STOOD_UP", cohortId, actorUserId,
                Map.of("cohortName", cohort.getName()));
            return new Gate4ResultDto(GateStateDto.passed());
        }

        auditEventService.record("GATE_FAILED", cohortId, actorUserId,
            Map.of("gate", 4, "errors", result.gate().errors()));
        return new Gate4ResultDto(GateStateDto.failed(result.gate().errors()));
    }

    public CohortResponse getCohort(UUID cohortId) {
        Cohort cohort = loadActiveCohort(cohortId);
        return toCohortResponse(cohort);
    }

    private Cohort loadActiveCohort(UUID cohortId) {
        return cohortRepository.findByIdAndIsActiveTrue(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with id: " + cohortId));
    }

    private CohortResponse toCohortResponse(Cohort cohort) {
        return new CohortResponse(
            cohort.getId(),
            cohort.getName(),
            cohort.getStartDate(),
            cohort.getEndDate(),
            cohort.getLifecycleState(),
            cohort.isLocked(),
            cohort.getSharepointFolderUrl()
        );
    }
}
