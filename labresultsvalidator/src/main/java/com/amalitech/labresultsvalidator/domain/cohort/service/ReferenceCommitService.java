package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandupPending;
import com.amalitech.labresultsvalidator.domain.cohort.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Lab;
import com.amalitech.labresultsvalidator.domain.cohort.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Learner;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.cohort.gate.ValidatedReferenceBundle;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortStandupPendingRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.InstructorContactRepository;
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

@Service
public class ReferenceCommitService {

    private final CohortRepository cohortRepository;
    private final CohortStandupPendingRepository pendingRepository;
    private final SpecializationRepository specializationRepository;
    private final LabModuleRepository labModuleRepository;
    private final LabRepository labRepository;
    private final LearnerRepository learnerRepository;
    private final InstructorContactRepository instructorContactRepository;
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

        if (!"DRAFT".equals(cohort.getLifecycleState())) {
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
        upsertInstructors(bundle, actorUserId);

        cohort.setLifecycleState("REFERENCE_ACCEPTED");
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

    private void clearPreviousReferenceData(UUID cohortId) {
        learnerRepository.deleteAllByCohortId(cohortId);

        List<Specialization> specs = specializationRepository.findAllByCohortId(cohortId);
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
                .code(row.code())
                .build();
            spec.setCreatedBy(actorUserId);
            spec.setUpdatedBy(actorUserId);
            Specialization saved = specializationRepository.save(spec);
            byCode.put(row.code(), saved);
        }
        return byCode;
    }

    private Map<String, LabModule> persistModules(
            ValidatedReferenceBundle bundle,
            Map<String, Specialization> specsByCode,
            UUID actorUserId) {
        Map<String, LabModule> byCode = new HashMap<>();
        for (ValidatedReferenceBundle.ModuleRow row : bundle.modules()) {
            Specialization spec = specsByCode.get(row.specializationCode());
            if (spec == null) {
                continue;
            }
            LabModule module = LabModule.builder()
                .specializationId(spec.getId())
                .name(row.name())
                .code(row.code())
                .sequence(row.sequence())
                .build();
            module.setCreatedBy(actorUserId);
            module.setUpdatedBy(actorUserId);
            LabModule saved = labModuleRepository.save(module);
            byCode.put(row.code(), saved);
        }
        return byCode;
    }

    private void persistLabs(
            ValidatedReferenceBundle bundle,
            Map<String, LabModule> modulesByCode,
            UUID actorUserId) {
        for (ValidatedReferenceBundle.LabRow row : bundle.labs()) {
            LabModule module = modulesByCode.get(row.moduleCode());
            if (module == null) {
                continue;
            }
            Lab lab = Lab.builder()
                .moduleId(module.getId())
                .title(row.title())
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
        for (ValidatedReferenceBundle.LearnerRow row : bundle.learners()) {
            Specialization spec = specsByCode.get(row.specializationCode());
            if (spec == null) {
                continue;
            }
            Learner learner = Learner.builder()
                .learnerId(row.learnerId())
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

    private void upsertInstructors(ValidatedReferenceBundle bundle, UUID actorUserId) {
        for (ValidatedReferenceBundle.InstructorContactRow row : bundle.instructors()) {
            if (instructorContactRepository.existsByInstructorId(row.instructorId())) {
                continue;
            }
            InstructorContact contact = InstructorContact.builder()
                .instructorId(row.instructorId())
                .fullName(row.fullName())
                .email(row.email())
                .build();
            contact.setCreatedBy(actorUserId);
            contact.setUpdatedBy(actorUserId);
            instructorContactRepository.save(contact);
        }
    }
}
