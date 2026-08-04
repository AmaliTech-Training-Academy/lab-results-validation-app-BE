package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.utils.SpecializationNameMatcher;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandupPending;
import com.amalitech.labresultsvalidator.domain.cohort.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.cohort.entity.InstructorSpecializationAssignment;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Lab;
import com.amalitech.labresultsvalidator.domain.cohort.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Learner;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.cohort.gate.ValidatedReferenceBundle;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortStandupPendingRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.InstructorSpecializationAssignmentRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.SpecializationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final CohortRepository cohortRepository;
    private final CohortStandupPendingRepository pendingRepository;
    private final SpecializationRepository specializationRepository;
    private final LabModuleRepository labModuleRepository;
    private final LabRepository labRepository;
    private final LearnerRepository learnerRepository;
    private final InstructorContactRepository instructorContactRepository;
    private final InstructorSpecializationAssignmentRepository instructorSpecializationAssignmentRepository;
    private final AuditEventService auditEventService;
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
                    ? cohort.getSharepointItemId() : ""
            ));
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
        // Instructor-specialization assignments are cohort-scoped through the specialization they
        // reference, so they follow the same delete-then-recreate lifecycle as the specs themselves.
        // InstructorContact (the person-level identity) is NOT touched here — it's a global table
        // shared across every cohort, upserted by email in persistInstructors below, never deleted.
        instructorSpecializationAssignmentRepository.deleteAllBySpecializationIdIn(
            specs.stream().map(Specialization::getId).toList());
        for (Specialization spec : specs) {
            List<LabModule> modules = labModuleRepository.findAllBySpecializationIdIn(
                List.of(spec.getId()));
            for (LabModule module : modules) {
                List<Lab> labs = labRepository.findAllByModuleIdIn(List.of(module.getId()));
                labRepository.deleteAll(labs);
            }
            labModuleRepository.deleteAll(modules);
        }
        specializationRepository.deleteAll(specs);
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
                .learnerId(row.email())
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

            // InstructorContact is global (not cohort-scoped) — upsert by email rather than
            // delete-then-recreate, so an instructor already known from another cohort's commit
            // isn't duplicated or lost.
            InstructorContact instructor = instructorContactRepository.findByEmailIgnoreCase(row.email())
                .orElseGet(() -> {
                    InstructorContact created = InstructorContact.builder()
                        .instructorId(UUID.randomUUID().toString())
                        .email(row.email())
                        .fullName(row.fullName())
                        .isActive(true)
                        .build();
                    created.setCreatedBy(actorUserId);
                    created.setUpdatedBy(actorUserId);
                    return instructorContactRepository.save(created);
                });

            if (!instructor.getFullName().equals(row.fullName())) {
                instructor.setFullName(row.fullName());
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
