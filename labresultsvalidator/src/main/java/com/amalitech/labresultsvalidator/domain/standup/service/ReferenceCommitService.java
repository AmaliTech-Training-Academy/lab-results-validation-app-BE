package com.amalitech.labresultsvalidator.domain.standup.service;

import com.amalitech.labresultsvalidator.domain.auditlog.service.AuditEventService;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.utils.SpecializationNameMatcher;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.standup.entity.CohortStandupPending;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorSpecializationAssignment;
import com.amalitech.labresultsvalidator.domain.reference.entity.Lab;
import com.amalitech.labresultsvalidator.domain.reference.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.reference.entity.Learner;
import com.amalitech.labresultsvalidator.domain.reference.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.standup.gate.ValidatedReferenceBundle;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.standup.repository.CohortStandupPendingRepository;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorSpecializationAssignmentRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.SpecializationRepository;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReferenceCommitService {

    private static final Logger LOG = LoggerFactory.getLogger(ReferenceCommitService.class);

    private final CohortRepository cohortRepository;
    private final CohortStandupPendingRepository pendingRepository;
    private final SpecializationRepository specializationRepository;
    private final LabModuleRepository labModuleRepository;
    private final LabRepository labRepository;
    private final LearnerRepository learnerRepository;
    private final InstructorContactRepository instructorContactRepository;
    private final InstructorSpecializationAssignmentRepository instructorSpecializationAssignmentRepository;
    private final AuditEventService auditEventService;
    private final GraphDriveService graphDriveService;
    private final ObjectMapper objectMapper;

    public ReferenceCommitService(
        CohortRepository cohortRepository,
        CohortStandupPendingRepository pendingRepository,
        SpecializationRepository specializationRepository,
        LabModuleRepository labModuleRepository,
        LabRepository labRepository,
        LearnerRepository learnerRepository,
        InstructorContactRepository instructorContactRepository,
        InstructorSpecializationAssignmentRepository instructorSpecializationAssignmentRepository,
        AuditEventService auditEventService,
        GraphDriveService graphDriveService,
        ObjectMapper objectMapper
    ) {
        this.cohortRepository = cohortRepository;
        this.pendingRepository = pendingRepository;
        this.specializationRepository = specializationRepository;
        this.labModuleRepository = labModuleRepository;
        this.labRepository = labRepository;
        this.learnerRepository = learnerRepository;
        this.instructorContactRepository = instructorContactRepository;
        this.instructorSpecializationAssignmentRepository = instructorSpecializationAssignmentRepository;
        this.auditEventService = auditEventService;
        this.graphDriveService = graphDriveService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void acceptAndCommit(UUID cohortId, UUID actorUserId) {
        CohortStandupPending pending = pendingRepository.findById(cohortId)
            .orElseThrow(() -> new UnprocessableEntityException(
                "No validated bundle found. Re-run stand-up Gates 1–3."));

        if (!pending.getExpiresAt().isAfter(OffsetDateTime.now())) {
            throw new UnprocessableEntityException(
                "Stand-up result has expired. Re-run stand-up Gates 1–3.");
        }

        Cohort cohort = cohortRepository.findByIdAndIsActiveTrue(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with id: " + cohortId));

        if (cohort.getLifecycleState() != CohortLifecycleState.DRAFT) {
            throw new UnprocessableEntityException("Cohort must be in DRAFT state to accept reference.");
        }

        ValidatedReferenceBundle bundle;
        try {
            bundle = objectMapper.readValue(pending.getBundleJson(), ValidatedReferenceBundle.class);
        } catch (JsonProcessingException ex) {
            throw new UnprocessableEntityException(
                "Failed to deserialize validated bundle. Re-run stand-up Gates 1–3.");
        }

        clearPreviousReferenceData(cohortId);

        Map<String, Specialization> savedSpecsByCode = persistSpecializations(bundle, cohortId, actorUserId);
        Map<String, LabModule> savedModulesByCode = persistModules(bundle, savedSpecsByCode, actorUserId);
        persistLabs(bundle, savedModulesByCode, actorUserId);
        persistLearners(bundle, cohortId, savedSpecsByCode, actorUserId);
        persistInstructors(bundle, savedSpecsByCode, actorUserId);

        cohort.setLifecycleState(CohortLifecycleState.REFERENCE_ACCEPTED);
        cohort.setReferenceAcceptedAt(OffsetDateTime.now());
        cohort.setReferenceAcceptedBy(actorUserId);
        cohortRepository.save(cohort);

        pendingRepository.deleteById(cohortId);

        auditEventService.record("REFERENCE_ACCEPTED", cohortId, actorUserId,
            Map.of(
                "sharepointFolderUrl", cohort.getSharepointFolderUrl() != null
                    ? cohort.getSharepointFolderUrl() : "",
                "driveId", cohort.getSharepointDriveId() != null
                    ? cohort.getSharepointDriveId() : "",
                "itemId", cohort.getSharepointItemId() != null
                    ? cohort.getSharepointItemId() : "",
                "sharepointVersionId", resolveSharepointVersionId(cohort)
            ));
    }

    /**
     * D2 AC2 — the reference folder's content tag at accept time, so the audit trail captures
     * which SharePoint version the bundle was accepted from. A metadata-fetch failure must not
     * block acceptance itself, so this degrades to "" rather than propagating.
     */
    private String resolveSharepointVersionId(Cohort cohort) {
        String driveId = cohort.getSharepointDriveId();
        String itemId = cohort.getSharepointItemId();
        if (driveId == null || itemId == null) {
            return "";
        }
        try {
            String versionId = graphDriveService.getItem(driveId, itemId).versionId();
            return versionId != null ? versionId : "";
        } catch (GraphAccessException ex) {
            LOG.warn("Could not resolve SharePoint version for cohort {}: {}", cohort.getId(), ex.getMessage(), ex);
            return "";
        }
    }

    @Transactional
    public void discardAndReset(UUID cohortId, UUID actorUserId) {
        Cohort cohort = cohortRepository.findByIdAndIsActiveTrue(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with id: " + cohortId));

        if (cohort.getLifecycleState() != CohortLifecycleState.REFERENCE_ACCEPTED) {
            throw new UnprocessableEntityException(
                "Reference data can only be discarded when cohort is in REFERENCE_ACCEPTED state.");
        }

        clearPreviousReferenceData(cohortId);

        cohort.setLifecycleState(CohortLifecycleState.DRAFT);
        cohort.setReferenceAcceptedAt(null);
        cohort.setReferenceAcceptedBy(null);
        cohortRepository.save(cohort);

        auditEventService.record("DISCARD_RESET", cohortId, actorUserId, null);
    }

    private void clearPreviousReferenceData(UUID cohortId) {
        learnerRepository.deleteAllByCohortId(cohortId);

        List<Specialization> specs = specializationRepository.findAllByCohortId(cohortId);
        List<UUID> specIds = specs.stream().map(Specialization::getId).toList();

        // Instructor-specialization assignments are cohort-scoped through the specialization they
        // reference, so they follow the same delete-then-recreate lifecycle as the specs themselves.
        // InstructorContact (the person-level identity) is NOT touched here — it's a global table
        // shared across every cohort, upserted by email in persistInstructors below, never deleted.
        instructorSpecializationAssignmentRepository.deleteAllBySpecializationIdIn(specIds);

        // Batched as two IN (...) queries across the whole cohort rather than one query per
        // specialization/module — a cohort with N specializations previously issued ~2N extra
        // reads (and per-entity deletes) every time reference data was accepted, discarded, or
        // re-run.
        List<LabModule> modules = labModuleRepository.findAllBySpecializationIdIn(specIds);
        List<UUID> moduleIds = modules.stream().map(LabModule::getId).toList();
        labRepository.deleteAll(labRepository.findAllByModuleIdIn(moduleIds));
        labModuleRepository.deleteAll(modules);
        specializationRepository.deleteAllByCohortId(cohortId);
    }

    private Map<String, Specialization> persistSpecializations(
            ValidatedReferenceBundle bundle, UUID cohortId, UUID actorUserId) {
        Map<String, Specialization> byCode = new HashMap<>();
        for (ValidatedReferenceBundle.SpecializationRow row : bundle.specializations()) {
            Specialization spec = Specialization.builder()
                .cohortId(cohortId)
                .name(row.name())
                .code(row.specializationId())
                .build();
            spec.setCreatedBy(actorUserId);
            spec.setUpdatedBy(actorUserId);
            Specialization saved = specializationRepository.save(spec);
            byCode.put(row.specializationId(), saved);
        }
        return byCode;
    }

    private Map<String, LabModule> persistModules(
            ValidatedReferenceBundle bundle,
            Map<String, Specialization> specsByCode,
            UUID actorUserId) {
        Map<String, LabModule> byCode = new HashMap<>();
        for (ValidatedReferenceBundle.ModuleRow row : bundle.modules()) {
            Specialization spec = specsByCode.get(row.specializationId());
            if (spec == null) {
                continue;
            }
            LabModule module = LabModule.builder()
                .specializationId(spec.getId())
                .name(row.name())
                .code(row.moduleId())
                .build();
            module.setCreatedBy(actorUserId);
            module.setUpdatedBy(actorUserId);
            LabModule saved = labModuleRepository.save(module);
            byCode.put(row.moduleId(), saved);
        }
        return byCode;
    }

    private void persistLabs(
            ValidatedReferenceBundle bundle,
            Map<String, LabModule> modulesByCode,
            UUID actorUserId) {
        for (ValidatedReferenceBundle.LabRow row : bundle.labs()) {
            LabModule module = modulesByCode.get(row.moduleId());
            if (module == null) {
                continue;
            }
            Lab lab = Lab.builder()
                .moduleId(module.getId())
                .title(row.labTitle())
                .build();
            lab.setCreatedBy(actorUserId);
            lab.setUpdatedBy(actorUserId);
            labRepository.save(lab);
        }
    }

    private void persistLearners(
            ValidatedReferenceBundle bundle,
            UUID cohortId,
            Map<String, Specialization> specsByCode,
            UUID actorUserId) {
        // Trainees link by specialization name; build a normalized name→Specialization lookup.
        Map<String, Specialization> specsByName = specsByCode.values().stream()
            .collect(Collectors.toMap(
                s -> SpecializationNameMatcher.normalize(s.getName()),
                s -> s,
                (a, b) -> a
            ));

        for (ValidatedReferenceBundle.LearnerRow row : bundle.learners()) {
            SpecializationNameMatcher.MatchResult<Specialization> match =
                SpecializationNameMatcher.resolve(row.specialization(), specsByName);
            if (match.outcome() != SpecializationNameMatcher.MatchOutcome.MATCHED) {
                // Gate 3 already rejects unknown/ambiguous specializations before commit; this is a
                // defensive guard, not an expected path.
                continue;
            }
            Specialization spec = match.value();

            Learner learner = Learner.builder()
                .fullName(row.fullName())
                .email(row.email())
                .cohortId(cohortId)
                .specializationId(spec.getId())
                .build();
            learner.setCreatedBy(actorUserId);
            learner.setUpdatedBy(actorUserId);
            learnerRepository.save(learner);
        }
    }

    private void persistInstructors(
            ValidatedReferenceBundle bundle,
            Map<String, Specialization> specsByCode,
            UUID actorUserId) {
        Map<String, Specialization> specsByName = specsByCode.values().stream()
            .collect(Collectors.toMap(
                s -> SpecializationNameMatcher.normalize(s.getName()),
                s -> s,
                (a, b) -> a
            ));

        for (ValidatedReferenceBundle.InstructorRow row : bundle.instructors()) {
            SpecializationNameMatcher.MatchResult<Specialization> match =
                SpecializationNameMatcher.resolve(row.specialization(), specsByName);
            if (match.outcome() != SpecializationNameMatcher.MatchOutcome.MATCHED) {
                // Gate 3 already rejects unknown/ambiguous specializations before commit; this is a
                // defensive guard, not an expected path.
                continue;
            }
            Specialization spec = match.value();

            // InstructorContact is global (not cohort-scoped) — upsert by full name rather than
            // delete-then-recreate, so an instructor already known from another cohort's commit
            // isn't duplicated or lost. full_name, not email, is the real identity here: instructors
            // teach across cohorts and each cohort's Instructor Database is filled in independently,
            // so the same person's email can vary (typo, personal vs. work address, re-entry) across
            // cohorts while the grading sheets' Reviewer column only ever carries a name. Upserting
            // by email (the old behavior) let a repeated name with a different email create a second
            // InstructorContact row — and the weekly sync's reviewer resolution
            // (InstructorContactRepository.findByFullNameIgnoreCase) expects exactly one row per
            // name, so it throws instead of resolving as soon as that happens.
            InstructorContact instructor = instructorContactRepository.findByFullNameIgnoreCase(row.fullName())
                .orElseGet(() -> {
                    InstructorContact created = InstructorContact.builder()
                        .email(row.email())
                        .fullName(row.fullName())
                        .isActive(true)
                        .build();
                    created.setCreatedBy(actorUserId);
                    created.setUpdatedBy(actorUserId);
                    return instructorContactRepository.save(created);
                });

            // Keep the contact's email current, but never at the cost of colliding with a
            // different instructor's email (unique) — better to keep the existing, still-valid
            // email than to fail the whole cohort's reference commit over a mismatch.
            if (!instructor.getEmail().equalsIgnoreCase(row.email())
                    && !instructorContactRepository.existsByEmailIgnoreCase(row.email())) {
                instructor.setEmail(row.email());
                instructor.setUpdatedBy(actorUserId);
                instructor = instructorContactRepository.save(instructor);
            }

            InstructorSpecializationAssignment assignment = InstructorSpecializationAssignment.builder()
                .instructorContactId(instructor.getId())
                .specializationId(spec.getId())
                .build();
            assignment.setCreatedBy(actorUserId);
            assignment.setUpdatedBy(actorUserId);
            instructorSpecializationAssignmentRepository.save(assignment);
        }
    }
}
