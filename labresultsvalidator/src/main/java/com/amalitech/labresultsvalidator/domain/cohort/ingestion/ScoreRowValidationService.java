package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import com.amalitech.labresultsvalidator.domain.cohort.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Lab;
import com.amalitech.labresultsvalidator.domain.cohort.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Learner;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.cohort.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.SpecializationRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
@Component
public class ScoreRowValidationService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final LearnerRepository learnerRepository;
    private final LabModuleRepository labModuleRepository;
    private final LabRepository labRepository;
    private final InstructorContactRepository instructorContactRepository;
    private final SpecializationRepository specializationRepository;

    public ScoreRowValidationService(
        LearnerRepository learnerRepository,
        LabModuleRepository labModuleRepository,
        LabRepository labRepository,
        InstructorContactRepository instructorContactRepository,
        SpecializationRepository specializationRepository
    ) {
        this.learnerRepository = learnerRepository;
        this.labModuleRepository = labModuleRepository;
        this.labRepository = labRepository;
        this.instructorContactRepository = instructorContactRepository;
        this.specializationRepository = specializationRepository;
    }

    public record ValidationResult(List<ValidatedScoreRow> validRows, List<RowError> errors) {
    }

    public ValidationResult validate(UUID cohortId, List<ParsedScoreRow> rows) {
        Map<String, Learner> learnersByName = learnerRepository.findAllByCohortId(cohortId).stream()
            .collect(Collectors.toMap(
                l -> l.getFullName().trim().toLowerCase(Locale.ROOT),
                l -> l,
                (a, b) -> a
            ));
        Map<String, Map<UUID, Lab>> labsByTitleAndSpecId = buildLabsByTitleAndSpecId(cohortId);

        List<ValidatedScoreRow> validRows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();

        for (ParsedScoreRow row : rows) {
            RowError error = validateOne(row, learnersByName, labsByTitleAndSpecId, validRows);
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
    private Map<String, Map<UUID, Lab>> buildLabsByTitleAndSpecId(UUID cohortId) {
        List<UUID> specIds = specializationRepository.findAllByCohortId(cohortId).stream()
            .map(Specialization::getId)
            .toList();

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
                                 List<ValidatedScoreRow> validRows) {
        // F1 — required fields non-blank.
        if (isBlank(row.nspName())) {
            return fieldError(row, "F1-BLANK-NSP", "Name of NSP is blank.");
        }
        if (isBlank(row.labTitle())) {
            return fieldError(row, "F1-BLANK-LAB-TITLE", "Lab Title is blank.");
        }
        if (isBlank(row.reviewDateRaw())) {
            return fieldError(row, "F1-BLANK-REVIEW-DATE", "Review Date is blank.");
        }
        if (isBlank(row.totalScoreRaw())) {
            // Not yet graded — a real, identified row awaiting a score. Not an error; just
            // skipped silently (neither committed nor reported) until a score is filled in.
            return null;
        }

        // F3 — review date parses to a valid date.
        if (row.reviewDate() == null) {
            return fieldError(row, "F3-INVALID-DATE",
                "Review Date '" + row.reviewDateRaw() + "' is not a valid date.");
        }

        // F2 — total score numeric and within range after ×100.
        if (row.totalScore() == null) {
            return fieldError(row, "F2-INVALID-SCORE",
                "Total Score '" + row.totalScoreRaw() + "' is not numeric.");
        }
        BigDecimal score = row.totalScore().multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
        if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(HUNDRED) > 0) {
            return fieldError(row, "F2-SCORE-OUT-OF-RANGE",
                "Total Score '" + row.totalScoreRaw() + "' resolves to " + score + ", outside 0-100.");
        }

        // R1 — NSP resolves to an active learner in this cohort.
        Learner learner = learnersByName.get(row.nspName().trim().toLowerCase(Locale.ROOT));
        if (learner == null) {
            return fieldError(row, "R1-UNKNOWN-NSP",
                "NSP '" + row.nspName() + "' does not match any learner in this cohort.");
        }

        // R4 — Lab Title resolves to a lab, cross-referenced against the NSP's specialization.
        Map<UUID, Lab> labsForTitle = labsByTitleAndSpecId.get(row.labTitle().trim().toLowerCase(Locale.ROOT));
        if (labsForTitle == null || labsForTitle.isEmpty()) {
            return fieldError(row, "R4-UNKNOWN-LAB",
                "Lab Title '" + row.labTitle() + "' does not match any lab configured for this cohort.");
        }
        Lab lab = labsForTitle.get(learner.getSpecializationId());
        if (lab == null) {
            return fieldError(row, "R4-LAB-SPEC-MISMATCH",
                "NSP '" + row.nspName() + "' specialization '" + specializationName(learner.getSpecializationId())
                    + "' does not match the specialization configured for lab '" + row.labTitle() + "'.");
        }

        // Reviewer → InstructorContact. Unresolved is non-blocking (B6 AC4/B12 AC3).
        UUID instructorContactId = null;
        if (!isBlank(row.reviewer())) {
            instructorContactId = instructorContactRepository.findByInstructorId(row.reviewer().trim())
                .map(InstructorContact::getId)
                .orElse(null);
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

    private RowError fieldError(ParsedScoreRow row, String rule, String message) {
        return new RowError(row.fileName(), row.location(), rule, message);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String specializationName(UUID specializationId) {
        return specializationRepository.findById(specializationId)
            .map(Specialization::getName)
            .orElse(specializationId.toString());
    }
}
