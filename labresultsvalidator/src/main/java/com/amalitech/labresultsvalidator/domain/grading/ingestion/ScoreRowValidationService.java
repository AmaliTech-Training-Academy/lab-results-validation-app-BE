package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorSpecializationAssignment;
import com.amalitech.labresultsvalidator.domain.reference.entity.Lab;
import com.amalitech.labresultsvalidator.domain.reference.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.reference.entity.Learner;
import com.amalitech.labresultsvalidator.domain.reference.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorSpecializationAssignmentRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.SpecializationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * B6 (normalization) + B7 (field/referential validation) for the recurring grading-ingestion
 * pipeline. No {@code Status=READY} filtering — the finalized model drops that assumption; every
 * row {@link ScoreRowParser} produced is validated.
 *
 * <p>Lab resolution mirrors {@code Gate4ScoreSheetValidator}'s stand-up-time check exactly: a row
 * resolves by {@code (Lab Title, NSP's specialization)}, not via the sheet name. The sheet name
 * carries no meaning here beyond a label for error locations — there is no per-specialization
 * module code/phase concept in the sheet-naming convention actually used.
 */
@Slf4j
@Component
public class ScoreRowValidationService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final LearnerRepository learnerRepository;
    private final LabModuleRepository labModuleRepository;
    private final LabRepository labRepository;
    private final InstructorContactRepository instructorContactRepository;
    private final InstructorSpecializationAssignmentRepository instructorSpecializationAssignmentRepository;
    private final SpecializationRepository specializationRepository;

    public ScoreRowValidationService(
        LearnerRepository learnerRepository,
        LabModuleRepository labModuleRepository,
        LabRepository labRepository,
        InstructorContactRepository instructorContactRepository,
        InstructorSpecializationAssignmentRepository instructorSpecializationAssignmentRepository,
        SpecializationRepository specializationRepository
    ) {
        this.learnerRepository = learnerRepository;
        this.labModuleRepository = labModuleRepository;
        this.labRepository = labRepository;
        this.instructorContactRepository = instructorContactRepository;
        this.instructorSpecializationAssignmentRepository = instructorSpecializationAssignmentRepository;
        this.specializationRepository = specializationRepository;
    }

    public record ValidationResult(List<ValidatedScoreRow> validRows, List<RowError> errors) {
    }

    public ValidationResult validate(UUID cohortId, List<ParsedScoreRow> rows) {
        List<UUID> specIds = specializationRepository.findAllByCohortId(cohortId).stream()
            .map(Specialization::getId)
            .toList();

        Map<String, Learner> learnersByName = learnerRepository.findAllByCohortId(cohortId).stream()
            .collect(Collectors.toMap(
                l -> l.getFullName().trim().toLowerCase(Locale.ROOT),
                l -> l,
                (a, b) -> a
            ));
        Map<String, Map<UUID, Lab>> labsByTitleAndSpecId = buildLabsByTitleAndSpecId(specIds);

        // instructor_contacts is a global, cross-cohort table (the same person can teach several
        // cohorts), so a Reviewer name can't be resolved against it wholesale — that would let a
        // name belonging to an instructor who has never taught in this cohort (or, worse, a
        // different person elsewhere who happens to share a name) resolve here anyway. Restrict
        // resolution to instructors actually assigned to one of this cohort's specializations.
        Set<UUID> instructorIdsForCohort = instructorSpecializationAssignmentRepository
            .findAllBySpecializationIdIn(specIds).stream()
            .map(InstructorSpecializationAssignment::getInstructorContactId)
            .collect(Collectors.toSet());

        List<ValidatedScoreRow> validRows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();

        for (ParsedScoreRow row : rows) {
            RowError error = validateOne(row, learnersByName, labsByTitleAndSpecId, instructorIdsForCohort, validRows);
            if (error != null) {
                errors.add(error);
            }
        }

        return new ValidationResult(validRows, errors);
    }

    // A lab title may be configured under more than one specialization (shared lab), so each
    // title maps to the specific Lab configured per specialization — mirrors
    // Gate4ScoreSheetValidator.buildLabTitleToSpecIds, but keeps the actual Lab (not just a
    // membership check) since a real labId is needed to commit.
    private Map<String, Map<UUID, Lab>> buildLabsByTitleAndSpecId(List<UUID> specIds) {
        List<LabModule> modules = labModuleRepository.findAllBySpecializationIdIn(specIds);
        Map<UUID, UUID> specIdByModuleId = modules.stream()
            .collect(Collectors.toMap(LabModule::getId, LabModule::getSpecializationId));

        List<UUID> moduleIds = modules.stream().map(LabModule::getId).toList();
        List<Lab> labs = labRepository.findAllByModuleIdIn(moduleIds);

        Map<String, Map<UUID, Lab>> labsByTitleAndSpecId = new HashMap<>();
        for (Lab lab : labs) {
            UUID specId = specIdByModuleId.get(lab.getModuleId());
            if (specId == null) {
                continue;
            }
            labsByTitleAndSpecId
                .computeIfAbsent(lab.getTitle().trim().toLowerCase(Locale.ROOT), k -> new HashMap<>())
                .putIfAbsent(specId, lab);
        }
        return labsByTitleAndSpecId;
    }

    private RowError validateOne(ParsedScoreRow row, Map<String, Learner> learnersByName,
                                 Map<String, Map<UUID, Lab>> labsByTitleAndSpecId,
                                 Set<UUID> instructorIdsForCohort,
                                 List<ValidatedScoreRow> validRows) {
        // Reviewer → InstructorContact resolved up front (matched by full name, not instructorId —
        // instructorId is now system-generated, not sheet-sourced) so that whatever error a row
        // ends up failing with below, it still carries the correctly-resolved instructor. An
        // unresolved reviewer is no longer silently allowed through (B6 AC4/B12 AC3 superseded):
        // it becomes its own hard failure, R5-UNKNOWN-REVIEWER, further down.
        UUID instructorContactId = resolveInstructor(row.reviewer(), instructorIdsForCohort);

        // F1 — required fields non-blank.
        if (isBlank(row.nspName())) {
            return fieldError(row, "F1-BLANK-NSP", "Name of NSP is blank.", instructorContactId);
        }
        if (isBlank(row.labTitle())) {
            return fieldError(row, "F1-BLANK-LAB-TITLE", "Lab Title is blank.", instructorContactId);
        }
        if (isBlank(row.reviewDateRaw())) {
            return fieldError(row, "F1-BLANK-REVIEW-DATE", "Review Date is blank.", instructorContactId);
        }
        if (isBlank(row.totalScoreRaw())) {
            // Not yet graded — a real, identified row awaiting a score. Not an error; just
            // skipped silently (neither committed nor reported) until a score is filled in.
            return null;
        }

        // F3 — review date parses to a valid date.
        if (row.reviewDate() == null) {
            return fieldError(row, "F3-INVALID-DATE",
                "Review Date '" + row.reviewDateRaw() + "' is not a valid date.", instructorContactId);
        }

        // F2 — total score numeric and within range 1-100. No whole-number requirement: a score can
        // carry a fractional grade (e.g. 30.565), which is simply rounded to 2dp — the range check
        // is the only thing that can reject a row here.
        if (row.totalScore() == null) {
            return fieldError(row, "F2-INVALID-SCORE",
                "Total Score '" + row.totalScoreRaw() + "' is not numeric.", instructorContactId);
        }
        BigDecimal score = row.totalScore().setScale(2, RoundingMode.HALF_UP);
        if (score.compareTo(BigDecimal.ONE) < 0 || score.compareTo(HUNDRED) > 0) {
            return fieldError(row, "F2-SCORE-OUT-OF-RANGE",
                "Total Score '" + row.totalScoreRaw() + "' is outside 1-100.", instructorContactId);
        }

        // R1 — NSP resolves to an active learner in this cohort.
        Learner learner = learnersByName.get(row.nspName().trim().toLowerCase(Locale.ROOT));
        if (learner == null) {
            return fieldError(row, "R1-UNKNOWN-NSP",
                "NSP '" + row.nspName() + "' does not match any learner in this cohort.", instructorContactId);
        }

        // R4 — Lab Title resolves to a lab, cross-referenced against the NSP's specialization.
        Map<UUID, Lab> labsForTitle = labsByTitleAndSpecId.get(row.labTitle().trim().toLowerCase(Locale.ROOT));
        if (labsForTitle == null || labsForTitle.isEmpty()) {
            return fieldError(row, "R4-UNKNOWN-LAB",
                "Lab Title '" + row.labTitle() + "' does not match any lab configured for this cohort.",
                instructorContactId);
        }
        Lab lab = labsForTitle.get(learner.getSpecializationId());
        if (lab == null) {
            return fieldError(row, "R4-LAB-SPEC-MISMATCH",
                "NSP '" + row.nspName() + "' specialization '" + specializationName(learner.getSpecializationId())
                    + "' does not match the specialization configured for lab '" + row.labTitle() + "'.",
                instructorContactId);
        }

        // R5 — reviewer must resolve to a known, active instructor. Unlike R1/R4 above, a failure
        // here carries no instructorContactId — there is no one to attribute this row's digest to,
        // so it routes to the admin notification instead of an instructor's.
        if (instructorContactId == null) {
            return fieldError(row, "R5-UNKNOWN-REVIEWER",
                "Reviewer '" + row.reviewer() + "' does not match any instructor assigned to this cohort.", null);
        }

        validRows.add(new ValidatedScoreRow(
            row.fileName(),
            row.sheetName(),
            row.rowNum(),
            learner.getId(),
            lab.getId(),
            instructorContactId,
            row.nspName().trim().toLowerCase(Locale.ROOT),
            row.reviewDate(),
            score
        ));
        return null;
    }

    private UUID resolveInstructor(String reviewer, Set<UUID> instructorIdsForCohort) {
        if (isBlank(reviewer)) {
            return null;
        }
        // A name match alone isn't enough: instructor_contacts is global, so it can hold an
        // instructor who has never taught in this cohort. Only accept the match if that
        // instructor is actually assigned to one of this cohort's specializations.
        return instructorContactRepository.findByFullNameIgnoreCase(reviewer.trim())
            .map(InstructorContact::getId)
            .filter(instructorIdsForCohort::contains)
            .orElse(null);
    }

    private RowError fieldError(ParsedScoreRow row, String rule, String message, UUID instructorContactId) {
        return new RowError(row.fileName(), row.location(), rule, message, instructorContactId,
            row.labTitle());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String specializationName(UUID specializationId) {
        // A learner with no specialization assigned (incomplete reference data) must not crash row
        // validation — repository.findById(null) throws before ever reaching the fallback below.
        if (specializationId == null) {
            return "(none assigned)";
        }
        return specializationRepository.findById(specializationId)
            .map(Specialization::getName)
            .orElse(specializationId.toString());
    }
}
