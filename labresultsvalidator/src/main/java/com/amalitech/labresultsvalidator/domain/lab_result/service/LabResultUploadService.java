package com.amalitech.labresultsvalidator.domain.lab_result.service;

import com.amalitech.labresultsvalidator.common.csv.CsvParseResult;
import com.amalitech.labresultsvalidator.common.csv.CsvParserService;
import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import com.amalitech.labresultsvalidator.common.csv.CsvWriterService;
import com.amalitech.labresultsvalidator.common.csv.MalformedCsvException;
import com.amalitech.labresultsvalidator.common.csv.ParsedRow;
import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultResponse;
import com.amalitech.labresultsvalidator.common.utils.Sha256Util;
import com.amalitech.labresultsvalidator.domain.csvUploads.dto.CsvUploadFilterRequest;
import com.amalitech.labresultsvalidator.domain.csvUploads.dto.CsvUploadResponse;
import com.amalitech.labresultsvalidator.domain.csvUploads.entity.CsvUpload;
import com.amalitech.labresultsvalidator.domain.csvUploads.repository.CsvUploadRepository;
import com.amalitech.labresultsvalidator.domain.csvUploads.repository.CsvUploadSpecification;
import com.amalitech.labresultsvalidator.domain.enums.LearnerStatus;
import com.amalitech.labresultsvalidator.domain.enums.UploadStatus;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.lab.entity.Lab;
import com.amalitech.labresultsvalidator.domain.lab.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultCorrectionRow;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultCsvRow;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultUploadResponse;
import com.amalitech.labresultsvalidator.domain.lab_result.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.lab_result.repository.LabResultRepository;
import com.amalitech.labresultsvalidator.domain.learner.entity.Learner;
import com.amalitech.labresultsvalidator.domain.learner.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.module.repository.ModuleRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.entity.UserModuleAssignment;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.repository.UserModuleAssignmentRepository;
import com.opencsv.CSVWriter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bulk import of lab results from a CSV file, running the full PRD validation pipeline (V1–V17) and
 * reporting per-row outcomes.
 *
 * <p>Structural problems (V1–V2) abort the whole file via {@link MalformedCsvException}. Every data
 * row is then graded independently: field-level checks (V3–V8), in-file duplicate detection (V16),
 * referential/consistency checks (V9–V15) against reference data, and a cohort-lock gate (V18).
 * Surviving rows are reconciled
 * against the {@code (learner, lab, attempt)} unique key (V17): a new key is inserted, a changed
 * value updates the existing result in place (instructors may correct a score), and an identical row
 * is skipped. Each upload is recorded in {@code csv_uploads} for audit, and a byte-identical
 * re-upload is rejected up front via its SHA-256 digest.
 */
@Service
@RequiredArgsConstructor
public class LabResultUploadService {

    private static final Logger LOG = LoggerFactory.getLogger(LabResultUploadService.class);
    /** Same permissive email shape used by the learner roster import (V8). */
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[\\w.+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");
    // Matches PostgreSQL detail: "Key (column)=(value) already exists."
    private static final Pattern DUPLICATE_KEY_DETAIL =
        Pattern.compile("Key \\(([^)]+)\\)=\\(([^)]+)\\)");
    private static final String[] RESULT_HEADERS = {
        "LEARNER_EMAIL", "COHORT_NAME", "SPECIALIZATION_NAME", "MODULE_NAME",
        "LAB_TITLE", "SCORE", "MAX_SCORE", "ATTEMPT_NUMBER", "SUBMITTED_ON", "GRADED_BY"
    };

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
    public LabResultUploadResponse bulkUpload(MultipartFile file) {
        String sha256 = Sha256Util.sha256Hex(readBytes(file));
        csvUploadRepository.findByFileSha256(sha256).ifPresent(prior -> {
            throw new DuplicateResourceException(
                "A file with identical content was already uploaded on "
                    + prior.getUploadedAt()
                    + " (filename: \"" + prior.getFilename() + "\"). "
                    + "Renaming the file does not count as a new upload — "
                    + "the content must change for it to be processed again.");
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

        // Collect reconciled results paired with their source line number so that any
        // DB exception during the individual saves can be attributed to the right row.
        record PendingSave(long lineNumber, ResolutionKind kind, LabResult result) {}
        List<PendingSave> pending = new ArrayList<>();
        int skipped = 0;

        for (ValidatedRow v : fieldValid) {
            if (inFileDuplicates.contains(v.lineNumber())) {
                continue;
            }
            Resolution resolution;
            try {
                resolution = reconcile(v, actor, adminBypass, errors, rejectedLines);
            } catch (Exception ex) {
                LOG.error("Unexpected error reconciling row {}: {}", v.lineNumber(), ex.getMessage(), ex);
                errors.add(new CsvRowError(v.lineNumber(), null,
                    "Unexpected error processing row: " + ex.getMessage()));
                rejectedLines.add(v.lineNumber());
                continue;
            }
            switch (resolution.kind()) {
                case INSERT -> pending.add(new PendingSave(v.lineNumber(), ResolutionKind.INSERT, resolution.result()));
                case UPDATE -> pending.add(new PendingSave(v.lineNumber(), ResolutionKind.UPDATE, resolution.result()));
                case SKIP -> skipped++;
                case REJECT -> { /* error already recorded */ }
                default -> throw new IllegalStateException(
                    "Unexpected resolution kind: " + resolution.kind());
            }
        }

        CsvUpload upload = csvUploadRepository.save(CsvUpload.builder()
            .uploadedByUser(actor)
            .filename(filename(file))
            .fileSha256(sha256)
            .uploadedAt(OffsetDateTime.now())
            .totalRows(parsed.totalRows())
            .build());

        // Save each row individually so a single DB failure is captured as a row-level
        // error without aborting the rest of the upload.
        int inserted = 0, updated = 0;
        for (PendingSave p : pending) {
            try {
                if (p.kind() == ResolutionKind.INSERT) {
                    p.result().setCsvUpload(upload);
                }
                labResultRepository.save(p.result());
                if (p.kind() == ResolutionKind.INSERT) {
                    inserted++;
                } else {
                    updated++;
                }
            } catch (DataIntegrityViolationException ex) {
                LOG.debug("DB constraint violation saving lab result at row {}: {}",
                    p.lineNumber(), ex.getMessage(), ex);
                errors.add(toDbRowError(p.lineNumber(), ex));
                rejectedLines.add(p.lineNumber());
            } catch (DataAccessException ex) {
                LOG.error("Database error saving lab result at row {}: {}",
                    p.lineNumber(), ex.getMostSpecificCause().getMessage(), ex);
                errors.add(new CsvRowError(p.lineNumber(), null,
                    "Failed to save to the database: " + ex.getMostSpecificCause().getMessage()));
                rejectedLines.add(p.lineNumber());
            } catch (RuntimeException ex) {
                LOG.error("Unexpected error processing lab result at row {}: {}",
                    p.lineNumber(), ex.getMessage(), ex);
                errors.add(new CsvRowError(p.lineNumber(), null,
                    "Failed to process row: " + ex.getMessage()));
                rejectedLines.add(p.lineNumber());
            }
        }

        List<Map<String, Object>> rejectedRows = buildRejectedRows(parsed, errors, rejectedLines);

        upload.setAcceptedRows(inserted + updated);
        upload.setRejectedRows(rejectedLines.size());
        int accepted = inserted + updated;
        UploadStatus dbStatus;
        if (accepted == 0 && parsed.totalRows() > 0) {
            dbStatus = UploadStatus.FAILED;
        } else if (!rejectedLines.isEmpty()) {
            dbStatus = UploadStatus.PARTIAL;
        } else {
            dbStatus = UploadStatus.COMPLETED;
        }
        upload.setStatus(dbStatus);
        upload.setErrorReportJson(buildReport(parsed.totalRows(), inserted, updated,
            skipped, rejectedLines.size(), errors, rejectedRows));
        csvUploadRepository.save(upload);

        UploadStatus instructorStatus =
            resolveInstructorStatus(accepted, parsed.totalRows(), rejectedLines.size());

        return LabResultUploadResponse.builder()
            .uploadId(upload.getId())
            .totalRows(parsed.totalRows())
            .insertedCount(inserted)
            .updatedCount(updated)
            .skippedCount(skipped)
            .rejectedCount(rejectedLines.size())
            .status(instructorStatus)
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

        User currentUser = (User) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        if (currentUser.getRole() != UserRole.INSTRUCTOR) {
            csvWriterService.writeTemplate(response.getWriter(), LabResultCsvRow.class);
            return;
        }

        List<UserModuleAssignment> assignments =
                userModuleAssignmentRepository.findAllByUserId(currentUser.getId());

        List<Lab> labs = assignments.isEmpty() ? List.of() :
                labRepository.findAllByModuleIdIn(
                        assignments.stream().map(a -> a.getModule().getId()).toList());

        CSVWriter csv = new CSVWriter(response.getWriter());

        csv.writeNext(RESULT_HEADERS);

        if (!labs.isEmpty()) {
            Lab first = labs.get(0);
            Module mod = first.getModule();
            Specialization spec = mod.getSpecialization();
            Cohort cohort = spec.getCohort();
            csv.writeNext(new String[]{
                "learner@example.com",
                cohort.getName(),
                spec.getName(),
                mod.getName(),
                first.getTitle(),
                first.getMaxScore().toPlainString(),
                first.getMaxScore().toPlainString(),
                "1",
                LocalDate.now().toString(),
                currentUser.getEmail()
            });
        } else {
            csv.writeNext(new String[]{
                "learner@example.com", "Cohort Name", "Specialization Name", "Module Name",
                "Lab Title", "85.5", "100.00", "1", LocalDate.now().toString(), "Instructor Name"
            });
        }

        csv.flush();
    }

    public void downloadLabTemplate(UUID labId, HttpServletResponse response) throws IOException {
        Lab lab = labRepository.findByIdWithModule(labId)
            .orElseThrow(() -> new ResourceNotFoundException("Lab not found with ID: " + labId));

        Module module = lab.getModule();
        Specialization spec = module.getSpecialization();
        Cohort cohort = spec.getCohort();

        User actor = currentUser();
        if (actor.getRole() == UserRole.INSTRUCTOR
                && !userModuleAssignmentRepository.existsByUserIdAndModuleId(
                        actor.getId(), module.getId())) {
            throw new UnprocessableEntityException(
                "You are not assigned to module '" + module.getName() + "'");
        }

        List<Learner> learners = learnerRepository
            .findAllByCohortIdAndSpecializationId(cohort.getId(), spec.getId());

        String safeName = lab.getTitle().replaceAll("[^a-zA-Z0-9_\\-]", "_");
        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"lab_results_" + safeName + "_template.csv\"");

        CSVWriter csv = new CSVWriter(response.getWriter());
        csv.writeNext(RESULT_HEADERS);

        if (learners.isEmpty()) {
            csv.writeNext(new String[]{
                "learner@example.com",
                cohort.getName(), spec.getName(), module.getName(), lab.getTitle(),
                lab.getMaxScore().toPlainString(), lab.getMaxScore().toPlainString(),
                "", "", actor.getEmail()
            });
        } else {
            for (Learner learner : learners) {
                csv.writeNext(new String[]{
                    learner.getEmail(),
                    cohort.getName(), spec.getName(), module.getName(), lab.getTitle(),
                    "", lab.getMaxScore().toPlainString(),
                    "", "", actor.getEmail()
                });
            }
        }

        csv.flush();
    }

    /**
     * Stream a corrections-only CSV for a previous upload: the original columns of every rejected
     * row plus a trailing {@code ERROR_MESSAGE} column. The instructor fixes these rows and
     * re-uploads. An upload with no rejected rows yields a header-only file.
     *
     * @param uploadId the {@code csv_uploads} record to export rejected rows from
     * @param response the servlet response to write the file to
     * @throws ResourceNotFoundException if no upload exists with that id (→ 404)
     * @throws IOException               if the response writer cannot be obtained
     */
    public void downloadCorrections(UUID uploadId, HttpServletResponse response) throws IOException {
        CsvUpload upload = csvUploadRepository.findById(uploadId)
            .orElseThrow(() -> new ResourceNotFoundException("Upload not found with ID: " + uploadId));

        List<LabResultCorrectionRow> rows = extractCorrectionRows(upload);

        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"corrections_" + uploadId + ".csv\"");
        if (rows.isEmpty()) {
            // OpenCSV writes nothing for an empty bean list, so emit the header explicitly.
            csvWriterService.writeTemplate(response.getWriter(), LabResultCorrectionRow.class);
        } else {
            csvWriterService.write(response.getWriter(), rows, LabResultCorrectionRow.class);
        }
    }

    /**
     * List all CSV uploads made by the authenticated instructor, with optional filters.
     * The {@code uploadedByEmail} field on the filter is ignored — results are always
     * scoped to the calling user.
     */
    public PagedResponse<CsvUploadResponse> listMyUploads(
            CsvUploadFilterRequest filter, Pageable pageable) {
        User actor = currentUser();
        return PagedResponse.of(
            csvUploadRepository.findAll(
                CsvUploadSpecification.withFiltersForOwner(filter, actor.getId()), pageable)
                .map(this::mapUploadToResponse)
        );
    }

    private CsvUploadResponse mapUploadToResponse(CsvUpload upload) {
        return CsvUploadResponse.builder()
            .id(upload.getId())
            .uploadedByEmail(upload.getUploadedByUser().getEmail())
            .filename(upload.getFilename())
            .fileSha256(upload.getFileSha256())
            .uploadedAt(upload.getUploadedAt())
            .totalRows(upload.getTotalRows())
            .acceptedRows(upload.getAcceptedRows())
            .rejectedRows(upload.getRejectedRows())
            .status(upload.getStatus() != null ? upload.getStatus().name() : null)
            .createdAt(upload.getCreatedAt())
            .updatedAt(upload.getUpdatedAt())
            .build();
    }

    /**
     * Retrieve the upload report for a previous CSV upload made by the authenticated instructor.
     *
     * @param uploadId the upload to retrieve
     * @return the upload report with instructor-facing status (COMPLETED / PARTIAL / FAILED)
     * @throws ResourceNotFoundException if no upload exists with that id or it belongs to another user
     */
    public LabResultUploadResponse getUploadReport(UUID uploadId) {
        User actor = currentUser();
        CsvUpload upload = csvUploadRepository.findById(uploadId)
            .orElseThrow(() -> new ResourceNotFoundException("Upload not found with ID: " + uploadId));

        if (!upload.getUploadedByUser().getId().equals(actor.getId())) {
            throw new ResourceNotFoundException("Upload not found with ID: " + uploadId);
        }

        Map<String, Object> report = upload.getErrorReportJson();

        int inserted = 0, updated = 0, skipped = 0;
        if (report != null && report.get("summary") instanceof Map<?, ?> summary) {
            inserted = toInt(summary.get("inserted"));
            updated  = toInt(summary.get("updated"));
            skipped  = toInt(summary.get("skipped"));
        }

        List<CsvRowError> errors = List.of();
        if (report != null && report.get("errors") instanceof List<?> rawErrors) {
            errors = rawErrors.stream()
                .filter(e -> e instanceof Map<?, ?>)
                .map(e -> {
                    Map<?, ?> m = (Map<?, ?>) e;
                    return new CsvRowError(
                        toLong(m.get("rowNumber")),
                        (String) m.get("field"),
                        (String) m.get("rule"),
                        (String) m.get("message"));
                })
                .toList();
        }

        int accepted = upload.getAcceptedRows();
        int rejected = upload.getRejectedRows();
        UploadStatus instructorStatus =
            resolveInstructorStatus(accepted, upload.getTotalRows(), rejected);

        return LabResultUploadResponse.builder()
            .uploadId(upload.getId())
            .totalRows(upload.getTotalRows())
            .insertedCount(inserted)
            .updatedCount(updated)
            .skippedCount(skipped)
            .rejectedCount(rejected)
            .status(instructorStatus)
            .errors(errors)
            .build();
    }

    private static int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
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
            } else if (maxScore.signum() <= 0) {
                errors.add(new CsvRowError(line, "MAX_SCORE", "V4",
                    "MAX_SCORE must be greater than 0, got " + maxScore));
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
        if (!adminBypass && !learner.getCohort().isLocked()) {
            return reject(errors, rejectedLines, line, "COHORT_NAME", "V18",
                "Cohort '" + learner.getCohort().getName()
                    + "' is not locked — results cannot be uploaded until the cohort is locked");
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

    private static CsvRowError toDbRowError(long lineNumber, DataIntegrityViolationException ex) {
        String cause = ex.getMostSpecificCause().getMessage();
        if (cause != null && cause.contains("duplicate key")) {
            Matcher m = DUPLICATE_KEY_DETAIL.matcher(cause);
            if (m.find()) {
                String cols  = m.group(1);
                String value = m.group(2);
                // uq_lab_result: UNIQUE (learner_id, lab_id, attempt_number)
                // The composite key uses FKs — surface ATTEMPT_NUMBER as the actionable field.
                if (cols.contains("learner_id") && cols.contains("lab_id")) {
                    return new CsvRowError(lineNumber, "ATTEMPT_NUMBER",
                        "A result for this learner, lab, and attempt number already exists");
                }
                // Fallback for any other single-column constraint on this table
                String field = cols.toUpperCase(Locale.ROOT);
                return new CsvRowError(lineNumber, field,
                    "'" + value + "' already exists — " + field + " must be unique");
            }
            return new CsvRowError(lineNumber, null,
                "A duplicate value violates a unique constraint");
        }
        return new CsvRowError(lineNumber, null,
            "Row could not be saved due to a database constraint violation");
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
        return String.join("\u0000",
            r.getLearnerEmail().trim().toLowerCase(Locale.ROOT),
            r.getCohortName().trim().toLowerCase(Locale.ROOT),
            r.getSpecializationName().trim().toLowerCase(Locale.ROOT),
            r.getModuleName().trim().toLowerCase(Locale.ROOT),
            r.getLabTitle().trim().toLowerCase(Locale.ROOT),
            String.valueOf(v.attemptNumber()));
    }

    private Map<String, Object> buildReport(
            int total, int inserted, int updated, int skipped, int rejected,
            List<CsvRowError> errors, List<Map<String, Object>> rejectedRows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRows", total);
        summary.put("inserted", inserted);
        summary.put("updated", updated);
        summary.put("skipped", skipped);
        summary.put("rejected", rejected);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", summary);
        report.put("errors", errors);
        report.put("rejectedRows", rejectedRows);
        return report;
    }

    // ── Corrections-only CSV: assemble (on upload) and read back (on download) ───

    /** CSV column names of the corrections file, used as the stable keys in the persisted report. */
    private static final String COL_LEARNER_EMAIL = "LEARNER_EMAIL";
    private static final String COL_COHORT_NAME = "COHORT_NAME";
    private static final String COL_SPECIALIZATION_NAME = "SPECIALIZATION_NAME";
    private static final String COL_MODULE_NAME = "MODULE_NAME";
    private static final String COL_LAB_TITLE = "LAB_TITLE";
    private static final String COL_SCORE = "SCORE";
    private static final String COL_MAX_SCORE = "MAX_SCORE";
    private static final String COL_ATTEMPT_NUMBER = "ATTEMPT_NUMBER";
    private static final String COL_SUBMITTED_ON = "SUBMITTED_ON";
    private static final String COL_GRADED_BY = "GRADED_BY";
    private static final String COL_ERROR_MESSAGE = "ERROR_MESSAGE";

    /**
     * Build one denormalized record per rejected line — the row's original column values plus an
     * aggregated {@code ERROR_MESSAGE} — ready to be persisted in the report and later streamed as
     * the corrections CSV. Rows that bound successfully carry their bound values; a row that failed
     * CSV binding has no bean, so its original cell values are recovered from the parser's raw-cell
     * snapshot, ensuring the corrections file always reproduces the full rejected row.
     */
    private List<Map<String, Object>> buildRejectedRows(
            CsvParseResult<LabResultCsvRow> parsed, List<CsvRowError> errors, Set<Long> rejectedLines) {
        Map<Long, LabResultCsvRow> boundByLine = new HashMap<>();
        for (ParsedRow<LabResultCsvRow> pr : parsed.validRows()) {
            boundByLine.put(pr.lineNumber(), pr.data());
        }
        Map<Long, Map<String, String>> rawByLine = parsed.rawCellsByLine();

        Map<Long, List<CsvRowError>> errorsByLine = new HashMap<>();
        for (CsvRowError e : errors) {
            errorsByLine.computeIfAbsent(e.rowNumber(), k -> new ArrayList<>()).add(e);
        }

        List<Map<String, Object>> rejectedRows = new ArrayList<>();
        for (Long line : rejectedLines.stream().sorted().toList()) {
            LabResultCsvRow r = boundByLine.get(line);
            Map<String, String> raw = rawByLine.getOrDefault(line, Map.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(COL_LEARNER_EMAIL, r == null ? raw.get(COL_LEARNER_EMAIL) : r.getLearnerEmail());
            row.put(COL_COHORT_NAME, r == null ? raw.get(COL_COHORT_NAME) : r.getCohortName());
            row.put(COL_SPECIALIZATION_NAME, r == null ? raw.get(COL_SPECIALIZATION_NAME) : r.getSpecializationName());
            row.put(COL_MODULE_NAME, r == null ? raw.get(COL_MODULE_NAME) : r.getModuleName());
            row.put(COL_LAB_TITLE, r == null ? raw.get(COL_LAB_TITLE) : r.getLabTitle());
            row.put(COL_SCORE, r == null ? raw.get(COL_SCORE) : r.getScore());
            row.put(COL_MAX_SCORE, r == null ? raw.get(COL_MAX_SCORE) : r.getMaxScore());
            row.put(COL_ATTEMPT_NUMBER, r == null ? raw.get(COL_ATTEMPT_NUMBER) : r.getAttemptNumber());
            row.put(COL_SUBMITTED_ON, r == null ? raw.get(COL_SUBMITTED_ON) : r.getSubmittedOn());
            row.put(COL_GRADED_BY, r == null ? raw.get(COL_GRADED_BY) : r.getGradedBy());
            row.put(COL_ERROR_MESSAGE, formatErrorMessage(errorsByLine.get(line)));
            rejectedRows.add(row);
        }
        return rejectedRows;
    }

    /** Join all of a line's errors into one cell, prefixing each with its field when known. */
    private static String formatErrorMessage(List<CsvRowError> lineErrors) {
        if (lineErrors == null || lineErrors.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (CsvRowError e : lineErrors) {
            parts.add(e.field() == null || e.field().isBlank()
                ? e.message()
                : e.field() + ": " + e.message());
        }
        return String.join(" | ", parts);
    }

    /** Re-hydrate the persisted rejected rows of an upload into corrections-CSV beans. */
    private List<LabResultCorrectionRow> extractCorrectionRows(CsvUpload upload) {
        Map<String, Object> report = upload.getErrorReportJson();
        if (report == null || !(report.get("rejectedRows") instanceof List<?> rows)) {
            return List.of();
        }
        List<LabResultCorrectionRow> corrections = new ArrayList<>();
        for (Object item : rows) {
            if (item instanceof Map<?, ?> row) {
                corrections.add(LabResultCorrectionRow.builder()
                    .learnerEmail(cell(row, COL_LEARNER_EMAIL))
                    .cohortName(cell(row, COL_COHORT_NAME))
                    .specializationName(cell(row, COL_SPECIALIZATION_NAME))
                    .moduleName(cell(row, COL_MODULE_NAME))
                    .labTitle(cell(row, COL_LAB_TITLE))
                    .score(cell(row, COL_SCORE))
                    .maxScore(cell(row, COL_MAX_SCORE))
                    .attemptNumber(cell(row, COL_ATTEMPT_NUMBER))
                    .submittedOn(cell(row, COL_SUBMITTED_ON))
                    .gradedBy(cell(row, COL_GRADED_BY))
                    .errorMessage(cell(row, COL_ERROR_MESSAGE))
                    .build());
            }
        }
        return corrections;
    }

    private static String cell(Map<?, ?> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
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

    private static UploadStatus resolveInstructorStatus(int accepted, int totalRows, int rejected) {
        if (accepted == 0 && totalRows > 0) {
            return UploadStatus.FAILED;
        }
        if (rejected > 0) {
            return UploadStatus.PARTIAL;
        }
        return UploadStatus.COMPLETED;
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

    public PagedResponse<LabResultResponse> getLabResultsByModule(UUID moduleId, Pageable pageable) {
        if (!moduleRepository.existsById(moduleId)) {
            throw new ResourceNotFoundException("Module not found with ID: " + moduleId);
        }
        Page<LabResultResponse> page = labResultRepository
                .findAllByModuleId(moduleId, pageable)
                .map(r -> LabResultResponse.builder()
                        .id(r.getId())
                        .learnerEmail(r.getLearner().getEmail())
                        .learnerName(r.getLearner().getFullName())
                        .labId(r.getLab().getId())
                        .labTitle(r.getLab().getTitle())
                        .score(r.getScore())
                        .maxScoreSnapshot(r.getMaxScoreSnapshot())
                        .attemptNumber(r.getAttemptNumber())
                        .submittedOn(r.getSubmittedOn())
                        .gradedBy(r.getGradedBy())
                        .build());
        return PagedResponse.of(page);
    }
}
