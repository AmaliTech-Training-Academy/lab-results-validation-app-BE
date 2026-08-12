package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.reference.entity.Lab;
import com.amalitech.labresultsvalidator.domain.reference.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.reference.entity.Learner;
import com.amalitech.labresultsvalidator.domain.reference.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.SpecializationRepository;
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
        // Reviewer → InstructorContact resolved up front (matched by full name, not instructorId —
        // instructorId is now system-generated, not sheet-sourced) so that whatever error a row
        // ends up failing with below, it still carries the correctly-resolved instructor. An
        // unresolved reviewer is no longer silently allowed through (B6 AC4/B12 AC3 superseded):
        // it becomes its own hard failure, R5-UNKNOWN-REVIEWER, further down.
        UUID instructorContactId = resolveInstructor(row.reviewer());

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

        // F2 — total score numeric, a whole number, and within range 1-100. Sheets record the
        // score directly as a whole number (e.g. 85) — no ×100 conversion, and no decimals.
        if (row.totalScore() == null) {
            return fieldError(row, "F2-INVALID-SCORE",
                "Total Score '" + row.totalScoreRaw() + "' is not numeric.", instructorContactId);
        }
        // Reject decimals outright (e.g. 0.92 or 92.5) rather than silently persisting a value
        // that doesn't match the whole-number-out-of-100 convention (see the double x100-scaling
        // incident this rule guards against).
        if (row.totalScore().stripTrailingZeros().scale() > 0) {
            return fieldError(row, "F2-SCORE-NOT-WHOLE-NUMBER",
                "Total Score '" + row.totalScoreRaw() + "' has a decimal point; scores must be a whole number 1-100."
                    + percentHint(row.totalScore()),
                instructorContactId);
        }
        BigDecimal score = row.totalScore().setScale(2, RoundingMode.HALF_UP);
        if (score.compareTo(BigDecimal.ONE) < 0 || score.compareTo(HUNDRED) > 0) {
            return fieldError(row, "F2-SCORE-OUT-OF-RANGE",
                "Total Score '" + row.totalScoreRaw() + "' resolves to " + score + ", outside 1-100.",
                instructorContactId);
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
                "Reviewer '" + row.reviewer() + "' does not match any active instructor.", null);
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

    private UUID resolveInstructor(String reviewer) {
        if (isBlank(reviewer)) {
            return null;
        }
        return instructorContactRepository.findByFullNameIgnoreCase(reviewer.trim())
            .map(InstructorContact::getId)
            .orElse(null);
    }

    // Detects the common Excel percent-format mistake: entering e.g. "92%" makes Excel store the
    // raw fraction 0.92, invisible to POI's getNumericCellValue() (see ScoreSheetRowReader). When
    // the rejected decimal times 100 lands cleanly on a whole number 1-100, surface that as an
    // actionable hint rather than leaving the instructor to guess what "not a whole number" means.
    private String percentHint(BigDecimal decimalScore) {
        BigDecimal asWhole = decimalScore.multiply(HUNDRED);
        if (asWhole.stripTrailingZeros().scale() > 0
            || asWhole.compareTo(BigDecimal.ONE) < 0
            || asWhole.compareTo(HUNDRED) > 0) {
            return "";
        }
        String whole = asWhole.stripTrailingZeros().toPlainString();
        return " This looks like a percentage-formatted cell showing '" + whole
            + "%' — re-enter the score as a whole number (e.g. " + whole + ").";
    }

    private RowError fieldError(ParsedScoreRow row, String rule, String message, UUID instructorContactId) {
        return new RowError(row.fileName(), row.location(), rule, message, instructorContactId,
            row.labTitle());
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
