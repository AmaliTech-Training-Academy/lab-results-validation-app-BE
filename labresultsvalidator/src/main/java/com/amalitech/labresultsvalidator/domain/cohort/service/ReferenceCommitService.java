package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandupPending;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Lab;
import com.amalitech.labresultsvalidator.domain.cohort.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Learner;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.cohort.gate.ValidatedReferenceBundle;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortStandupPendingRepository;
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
import java.util.Locale;
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
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    public ReferenceCommitService(
        CohortRepository cohortRepository,
        CohortStandupPendingRepository pendingRepository,
        SpecializationRepository specializationRepository,
        LabModuleRepository labModuleRepository,
        LabRepository labRepository,
        LearnerRepository learnerRepository,
        AuditEventService auditEventService,
        ObjectMapper objectMapper
    ) {
        this.cohortRepository = cohortRepository;
        this.pendingRepository = pendingRepository;
        this.specializationRepository = specializationRepository;
        this.labModuleRepository = labModuleRepository;
        this.labRepository = labRepository;
        this.learnerRepository = learnerRepository;
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

    private Specialization findSpecByPartialName(
            String traineeSpec, Map<String, Specialization> specsByName) {
        if (traineeSpec == null || traineeSpec.isBlank()) {
            return null;
        }
        String key = traineeSpec.toLowerCase(Locale.ROOT);
        Specialization exact = specsByName.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Specialization> entry : specsByName.entrySet()) {
            if (key.contains(entry.getKey()) || entry.getKey().contains(key)) {
                return entry.getValue();
            }
        }
        return null;
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
        int sequenceFallback = 0;
        for (ValidatedReferenceBundle.ModuleRow row : bundle.modules()) {
            Specialization spec = specsByCode.get(row.specializationId());
            if (spec == null) {
                continue;
            }
            sequenceFallback++;
            int sequence;
            try {
                sequence = Integer.parseInt(row.phase().trim());
                if (sequence <= 0) {
                    sequence = sequenceFallback;
                }
            } catch (NumberFormatException ex) {
                sequence = sequenceFallback;
            }
            LabModule module = LabModule.builder()
                .specializationId(spec.getId())
                .name(row.name())
                .code(row.moduleId())
                .sequence(sequence)
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
        // Trainees link by specialization name; build a name→Specialization lookup.
        Map<String, Specialization> specsByName = specsByCode.values().stream()
            .collect(Collectors.toMap(
                s -> s.getName().toLowerCase(Locale.ROOT),
                s -> s,
                (a, b) -> a
            ));

        for (ValidatedReferenceBundle.LearnerRow row : bundle.learners()) {
            Specialization spec = findSpecByPartialName(row.specialization(), specsByName);
            if (spec == null) {
                continue;
            }

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
}
