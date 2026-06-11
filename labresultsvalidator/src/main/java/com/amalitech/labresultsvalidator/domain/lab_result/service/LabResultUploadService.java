package com.amalitech.labresultsvalidator.domain.lab_result.service;

import com.amalitech.labresultsvalidator.common.csv.CsvParseResult;
import com.amalitech.labresultsvalidator.common.csv.CsvParserService;
import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import com.amalitech.labresultsvalidator.common.csv.CsvWriterService;
import com.amalitech.labresultsvalidator.common.csv.MalformedCsvException;
import com.amalitech.labresultsvalidator.common.csv.ParsedRow;
import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.utils.Sha256Util;
import com.amalitech.labresultsvalidator.domain.csvUploads.entity.CsvUpload;
import com.amalitech.labresultsvalidator.domain.csvUploads.repository.CsvUploadRepository;
import com.amalitech.labresultsvalidator.domain.enums.LearnerStatus;
import com.amalitech.labresultsvalidator.domain.enums.UploadStatus;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.lab.entity.Lab;
import com.amalitech.labresultsvalidator.domain.lab.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultCsvRow;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultUploadResponse;
import com.amalitech.labresultsvalidator.domain.lab_result.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.lab_result.repository.LabResultRepository;
import com.amalitech.labresultsvalidator.domain.learner.entity.Learner;
import com.amalitech.labresultsvalidator.domain.learner.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import com.amalitech.labresultsvalidator.domain.module.repository.ModuleRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.repository.UserModuleAssignmentRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bulk import of lab results from a CSV file, running the full PRD validation pipeline (V1–V17) and
 * reporting per-row outcomes.
 *
 * <p>Structural problems (V1–V2) abort the whole file via {@link MalformedCsvException}. Every data
 * row is then graded independently: field-level checks (V3–V8), in-file duplicate detection (V16),
 * and referential/consistency checks (V9–V15) against reference data. Surviving rows are reconciled
 * against the {@code (learner, lab, attempt)} unique key (V17): a new key is inserted, a changed
 * value updates the existing result in place (instructors may correct a score), and an identical row
 * is skipped. Each upload is recorded in {@code csv_uploads} for audit, and a byte-identical
 * re-upload is rejected up front via its SHA-256 digest.
 */
@Service
@RequiredArgsConstructor
public class LabResultUploadService {

    /** Same permissive email shape used by the learner roster import (V8). */
    private static final java.util.regex.Pattern EMAIL_PATTERN =
        java.util.regex.Pattern.compile("^[\\w.+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

    private final CsvParserService csvParserService;
    private final CsvWriterService csvWriterService;
    private final CsvUploadRepository csvUploadRepository;
    private final LabResultRepository labResultRepository;
    private final LearnerRepository learnerRepository;
    private final ModuleRepository moduleRepository;
    private final LabRepository labRepository;
    private final UserModuleAssignmentRepository userModuleAssignmentRepository;

    /**
     * Validate and import a CSV of lab results.
     *
     * @param file the uploaded CSV
     * @return per-row outcome counts and the row-level error report
     * @throws MalformedCsvException    for whole-file structural failures (→ 422)
     * @throws DuplicateResourceException if the exact file was already uploaded (→ 409)
     */
    @Transactional
    public LabResultUploadResponse bulkUpload(MultipartFile file) {
        String sha256 = Sha256Util.sha256Hex(readBytes(file));
        csvUploadRepository.findByFileSha256(sha256).ifPresent(prior -> {
            throw new DuplicateResourceException(
                "This file was already uploaded on " + prior.getUploadedAt() + ".");
        });

        CsvParseResult<LabResultCsvRow> parsed = csvParserService.parse(file, LabResultCsvRow.class);

        List<CsvRowError> errors = new ArrayList<>(parsed.errors());
        Set<Long> rejectedLines = new HashSet<>();
        parsed.errors().forEach(e -> rejectedLines.add(e.rowNumber()));

        List<ValidatedRow> fieldValid = validateFields(parsed.validRows(), errors, rejectedLines);
        Set<Long> inFileDuplicates = flagInFileDuplicates(fieldValid, errors, rejectedLines);

        User actor = currentUser();
        boolean adminBypass = actor.getRole() == UserRole.ADMIN
            || actor.getRole() == UserRole.SUPER_ADMIN;

        List<LabResult> toInsert = new ArrayList<>();
        List<LabResult> toUpdate = new ArrayList<>();
        int skipped = 0;

        for (ValidatedRow v : fieldValid) {
            if (inFileDuplicates.contains(v.lineNumber())) {
                continue;
            }
            Resolution resolution = reconcile(v, actor, adminBypass, errors, rejectedLines);
            switch (resolution.kind()) {
                case INSERT -> toInsert.add(resolution.result());
                case UPDATE -> toUpdate.add(resolution.result());
                case SKIP -> skipped++;
                case REJECT -> { /* error already recorded */ }
            }
        }

        int accepted = toInsert.size() + toUpdate.size();
        CsvUpload upload = csvUploadRepository.save(CsvUpload.builder()
            .uploadedByUser(actor)
            .filename(filename(file))
            .fileSha256(sha256)
            .uploadedAt(OffsetDateTime.now())
            .totalRows(parsed.totalRows())
            .acceptedRows(accepted)
            .rejectedRows(rejectedLines.size())
            .status(UploadStatus.COMPLETED)
            .errorReportJson(buildReport(parsed.totalRows(), toInsert.size(), toUpdate.size(),
                skipped, rejectedLines.size(), errors))
            .build());

        toInsert.forEach(lr -> lr.setCsvUpload(upload));
        labResultRepository.saveAll(toInsert);
        labResultRepository.saveAll(toUpdate);

        return LabResultUploadResponse.builder()
            .uploadId(upload.getId())
            .totalRows(parsed.totalRows())
            .insertedCount(toInsert.size())
            .updatedCount(toUpdate.size())
            .skippedCount(skipped)
            .rejectedCount(rejectedLines.size())
            .status(upload.getStatus())
            .errors(errors)
            .build();
    }

    /**
     * Stream a header-only CSV template with the correct lab-result column names.
     *
     * @param response the servlet response to write the file to
     * @throws IOException if the response writer cannot be obtained
     */
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"lab_results_upload_template.csv\"");
        csvWriterService.writeTemplate(response.getWriter(), LabResultCsvRow.class);
    }

    // ── Stage 2: field-level validation (V3–V8) ───────────────────────────────

    private List<ValidatedRow> validateFields(
            List<ParsedRow<LabResultCsvRow>> rows, List<CsvRowError> errors, Set<Long> rejectedLines) {
        List<ValidatedRow> valid = new ArrayList<>();
        for (ParsedRow<LabResultCsvRow> pr : rows) {
            LabResultCsvRow r = pr.data();
            long line = pr.lineNumber();
            boolean ok = true;

            ok &= requireNonBlank(r.getLearnerEmail(), line, "LEARNER_EMAIL", errors);
            ok &= requireNonBlank(r.getCohortName(), line, "COHORT_NAME", errors);
            ok &= requireNonBlank(r.getSpecializationName(), line, "SPECIALIZATION_NAME", errors);
            ok &= requireNonBlank(r.getModuleName(), line, "MODULE_NAME", errors);
            ok &= requireNonBlank(r.getLabTitle(), line, "LAB_TITLE", errors);

            if (notBlank(r.getLearnerEmail())
                    && !EMAIL_PATTERN.matcher(r.getLearnerEmail().trim()).matches()) {
                errors.add(new CsvRowError(line, "LEARNER_EMAIL", "V8",
                    "'" + r.getLearnerEmail() + "' is not a valid email address"));
                ok = false;
            }

            BigDecimal score = parseDecimal(r.getScore(), line, "SCORE", errors);
            BigDecimal maxScore = parseDecimal(r.getMaxScore(), line, "MAX_SCORE", errors);
            if (score == null || maxScore == null) {
                ok = false;
            } else if (score.signum() < 0 || score.compareTo(maxScore) > 0) {
                errors.add(new CsvRowError(line, "SCORE", "V5",
                    "Score " + score + " must be between 0 and max_score " + maxScore));
                ok = false;
            }

            Short attempt = parseAttempt(r.getAttemptNumber(), line, errors);
            if (attempt == null) {
                ok = false;
            }

            LocalDate submittedOn = parseDate(r.getSubmittedOn(), line, errors);
            if (submittedOn == null) {
                ok = false;
            }

            if (ok) {
                valid.add(new ValidatedRow(line, r, score, maxScore, attempt, submittedOn));
            } else {
                rejectedLines.add(line);
            }
        }
        return valid;
    }

    // ── Stage 3a: in-file duplicates (V16) ─────────────────────────────────────

    private Set<Long> flagInFileDuplicates(
            List<ValidatedRow> rows, List<CsvRowError> errors, Set<Long> rejectedLines) {
        Map<String, List<Long>> keyToLines = new HashMap<>();
        Map<Long, ValidatedRow> byLine = new HashMap<>();
        for (ValidatedRow v : rows) {
            byLine.put(v.lineNumber(), v);
            keyToLines.computeIfAbsent(dupKey(v), k -> new ArrayList<>()).add(v.lineNumber());
        }

        Set<Long> duplicates = new HashSet<>();
        for (List<Long> lines : keyToLines.values()) {
            if (lines.size() > 1) {
                for (Long line : lines) {
                    duplicates.add(line);
                    rejectedLines.add(line);
                    errors.add(new CsvRowError(line, "ATTEMPT_NUMBER", "V16",
                        "Duplicate (learner, lab, attempt) within this file"));
                }
            }
        }
        return duplicates;
    }

    // ── Stage 3b: referential checks (V9–V15) + reconcile (V17) ────────────────

    private Resolution reconcile(
            ValidatedRow v, User actor, boolean adminBypass,
            List<CsvRowError> errors, Set<Long> rejectedLines) {
        LabResultCsvRow r = v.raw();
        long line = v.lineNumber();

        Optional<Learner> learnerOpt = learnerRepository.findByEmailIgnoreCase(r.getLearnerEmail().trim());
        if (learnerOpt.isEmpty()) {
            return reject(errors, rejectedLines, line, "LEARNER_EMAIL", "V9",
                "Learner '" + r.getLearnerEmail() + "' not found");
        }
        Learner learner = learnerOpt.get();
        if (learner.getStatus() != LearnerStatus.ACTIVE) {
            return reject(errors, rejectedLines, line, "LEARNER_EMAIL", "V9",
                "Learner '" + r.getLearnerEmail() + "' is not active");
        }
        if (!learner.getCohort().getName().equalsIgnoreCase(r.getCohortName().trim())) {
            return reject(errors, rejectedLines, line, "COHORT_NAME", "V10",
                "Cohort '" + r.getCohortName() + "' does not match the learner's enrolled cohort '"
                    + learner.getCohort().getName() + "'");
        }
        if (!learner.getSpecialization().getName().equalsIgnoreCase(r.getSpecializationName().trim())) {
            return reject(errors, rejectedLines, line, "SPECIALIZATION_NAME", "V11",
                "Specialization '" + r.getSpecializationName()
                    + "' does not match the learner's enrolled specialization '"
                    + learner.getSpecialization().getName() + "'");
        }

        Optional<Module> moduleOpt = moduleRepository.findBySpecializationIdAndNameIgnoreCase(
            learner.getSpecialization().getId(), r.getModuleName().trim());
        if (moduleOpt.isEmpty()) {
            return reject(errors, rejectedLines, line, "MODULE_NAME", "V12",
                "Module '" + r.getModuleName() + "' not found in specialization '"
                    + learner.getSpecialization().getName() + "'");
        }
        Module module = moduleOpt.get();

        Optional<Lab> labOpt = labRepository.findByModuleIdAndTitleIgnoreCase(
            module.getId(), r.getLabTitle().trim());
        if (labOpt.isEmpty()) {
            return reject(errors, rejectedLines, line, "LAB_TITLE", "V13",
                "Lab '" + r.getLabTitle() + "' not found in module '" + module.getName() + "'");
        }
        Lab lab = labOpt.get();

        if (lab.getMaxScore().compareTo(v.maxScore()) != 0) {
            return reject(errors, rejectedLines, line, "MAX_SCORE", "V14",
                "MAX_SCORE " + v.maxScore() + " does not match the configured max score "
                    + lab.getMaxScore() + " for lab '" + lab.getTitle() + "'");
        }

        if (!adminBypass
                && !userModuleAssignmentRepository.existsByUserIdAndModuleId(actor.getId(), module.getId())) {
            return reject(errors, rejectedLines, line, "MODULE_NAME", "V15",
                "You are not authorized to upload results for module '" + module.getName() + "'");
        }

        Optional<LabResult> existingOpt = labResultRepository
            .findByLearnerIdAndLabIdAndAttemptNumber(learner.getId(), lab.getId(), v.attemptNumber());
        if (existingOpt.isEmpty()) {
            LabResult lr = LabResult.builder()
                .learner(learner)
                .lab(lab)
                .score(v.score())
                .maxScoreSnapshot(v.maxScore())
                .attemptNumber(v.attemptNumber())
                .submittedOn(v.submittedOn())
                .gradedBy(trimToNull(r.getGradedBy()))
                .build();
            lr.setCreatedBy(actor.getId());
            lr.setUpdatedBy(actor.getId());
            return Resolution.of(ResolutionKind.INSERT, lr);
        }

        LabResult existing = existingOpt.get();
        if (isUnchanged(existing, v, r)) {
            return Resolution.of(ResolutionKind.SKIP, existing);
        }
        existing.setScore(v.score());
        existing.setMaxScoreSnapshot(v.maxScore());
        existing.setSubmittedOn(v.submittedOn());
        existing.setGradedBy(trimToNull(r.getGradedBy()));
        existing.setUpdatedBy(actor.getId());
        return Resolution.of(ResolutionKind.UPDATE, existing);
    }

    private boolean isUnchanged(LabResult existing, ValidatedRow v, LabResultCsvRow r) {
        return existing.getScore().compareTo(v.score()) == 0
            && existing.getMaxScoreSnapshot().compareTo(v.maxScore()) == 0
            && existing.getSubmittedOn().equals(v.submittedOn())
            && Objects.equals(existing.getGradedBy(), trimToNull(r.getGradedBy()));
    }

    // ── Parsing / validation helpers ───────────────────────────────────────────

    private boolean requireNonBlank(String value, long line, String field, List<CsvRowError> errors) {
        if (value == null || value.isBlank()) {
            errors.add(new CsvRowError(line, field, "V3", field + " is required"));
            return false;
        }
        return true;
    }

    private BigDecimal parseDecimal(String value, long line, String field, List<CsvRowError> errors) {
        if (value == null || value.isBlank()) {
            errors.add(new CsvRowError(line, field, "V3", field + " is required"));
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            errors.add(new CsvRowError(line, field, "V4", "'" + value + "' is not a valid number"));
            return null;
        }
    }

    private Short parseAttempt(String value, long line, List<CsvRowError> errors) {
        if (value == null || value.isBlank()) {
            errors.add(new CsvRowError(line, "ATTEMPT_NUMBER", "V3", "ATTEMPT_NUMBER is required"));
            return null;
        }
        try {
            short attempt = Short.parseShort(value.trim());
            if (attempt != 1 && attempt != 2) {
                errors.add(new CsvRowError(line, "ATTEMPT_NUMBER", "V6",
                    "Attempt number must be 1 or 2, got " + attempt));
                return null;
            }
            return attempt;
        } catch (NumberFormatException e) {
            errors.add(new CsvRowError(line, "ATTEMPT_NUMBER", "V6",
                "'" + value + "' is not a valid attempt number"));
            return null;
        }
    }

    private LocalDate parseDate(String value, long line, List<CsvRowError> errors) {
        if (value == null || value.isBlank()) {
            errors.add(new CsvRowError(line, "SUBMITTED_ON", "V3", "SUBMITTED_ON is required"));
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            errors.add(new CsvRowError(line, "SUBMITTED_ON", "V7",
                "'" + value + "' is not an ISO-8601 date (YYYY-MM-DD)"));
            return null;
        }
    }

    private Resolution reject(
            List<CsvRowError> errors, Set<Long> rejectedLines,
            long line, String field, String rule, String message) {
        errors.add(new CsvRowError(line, field, rule, message));
        rejectedLines.add(line);
        return Resolution.rejected();
    }

    private String dupKey(ValidatedRow v) {
        LabResultCsvRow r = v.raw();
        return String.join("",
            r.getLearnerEmail().trim().toLowerCase(Locale.ROOT),
            r.getCohortName().trim().toLowerCase(Locale.ROOT),
            r.getSpecializationName().trim().toLowerCase(Locale.ROOT),
            r.getModuleName().trim().toLowerCase(Locale.ROOT),
            r.getLabTitle().trim().toLowerCase(Locale.ROOT),
            String.valueOf(v.attemptNumber()));
    }

    private Map<String, Object> buildReport(
            int total, int inserted, int updated, int skipped, int rejected, List<CsvRowError> errors) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRows", total);
        summary.put("inserted", inserted);
        summary.put("updated", updated);
        summary.put("skipped", skipped);
        summary.put("rejected", rejected);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", summary);
        report.put("errors", errors);
        return report;
    }

    private byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MalformedCsvException("CSV file is empty or missing.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new MalformedCsvException("Unable to read the uploaded CSV file.", e);
        }
    }

    private String filename(MultipartFile file) {
        String name = file.getOriginalFilename();
        return (name == null || name.isBlank()) ? "upload.csv" : name;
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private enum ResolutionKind { INSERT, UPDATE, SKIP, REJECT }

    private record Resolution(ResolutionKind kind, LabResult result) {
        static Resolution of(ResolutionKind kind, LabResult result) {
            return new Resolution(kind, result);
        }

        static Resolution rejected() {
            return new Resolution(ResolutionKind.REJECT, null);
        }
    }

    private record ValidatedRow(
        long lineNumber, LabResultCsvRow raw,
        BigDecimal score, BigDecimal maxScore, short attemptNumber, LocalDate submittedOn) {
    }
}
