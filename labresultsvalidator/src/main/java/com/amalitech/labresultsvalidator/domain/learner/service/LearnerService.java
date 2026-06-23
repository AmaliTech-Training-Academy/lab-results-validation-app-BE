package com.amalitech.labresultsvalidator.domain.learner.service;

import com.amalitech.labresultsvalidator.common.csv.CsvParseResult;
import com.amalitech.labresultsvalidator.common.csv.CsvParserService;
import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import com.amalitech.labresultsvalidator.common.csv.CsvWriterService;
import com.amalitech.labresultsvalidator.common.csv.MalformedCsvException;
import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.enums.LearnerStatus;
import com.amalitech.labresultsvalidator.domain.lab_result.repository.LabResultRepository;
import com.amalitech.labresultsvalidator.domain.learner.dto.BulkUploadResponse;
import com.amalitech.labresultsvalidator.domain.learner.dto.CreateLearnerRequest;
import com.amalitech.labresultsvalidator.domain.learner.dto.LearnerCsvRow;
import com.amalitech.labresultsvalidator.domain.learner.dto.LearnerResponse;
import com.amalitech.labresultsvalidator.domain.learner.dto.UpdateLearnerRequest;
import com.amalitech.labresultsvalidator.domain.learner.dto.UpdateLearnerStatusRequest;
import com.amalitech.labresultsvalidator.domain.learner.entity.Learner;
import com.amalitech.labresultsvalidator.domain.learner.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.specialization.repository.SpecializationRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LearnerService {

    private static final Logger LOG = LoggerFactory.getLogger(LearnerService.class);
    private static final long MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024;
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[\\w.+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");
    // Matches PostgreSQL detail: "Key (email)=(john@example.com) already exists."
    private static final Pattern DUPLICATE_KEY_DETAIL =
        Pattern.compile("Key \\(([^)]+)\\)=\\(([^)]+)\\)");

    private final LearnerRepository learnerRepository;
    private final CohortRepository cohortRepository;
    private final SpecializationRepository specializationRepository;
    private final LabResultRepository labResultRepository;
    private final CsvParserService csvParserService;
    private final CsvWriterService csvWriterService;

    // ── AC-1: Single learner creation ────────────────────────────────────────

    public LearnerResponse createLearner(CreateLearnerRequest request) {
        if (learnerRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new DuplicateResourceException(
                "A learner with email '" + request.getEmail() + "' already exists");
        }

        Cohort cohort = cohortRepository.findById(request.getCohortId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Cohort with id '" + request.getCohortId() + "' not found"));

        if (cohort.isLocked()) {
            throw new UnprocessableEntityException(
                "Cohort '" + cohort.getName() + "' is locked — learners cannot be added");
        }

        Specialization specialization = specializationRepository
            .findByIdAndCohortId(request.getSpecializationId(), request.getCohortId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Specialization with id '" + request.getSpecializationId()
                + "' not found in cohort '" + cohort.getName() + "'"));

        User actor = currentUser();
        Learner learner = Learner.builder()
            .fullName(request.getFullName())
            .email(request.getEmail().toLowerCase())
            .cohort(cohort)
            .specialization(specialization)
            .status(LearnerStatus.ACTIVE)
            .build();
        learner.setCreatedBy(actor.getId());
        learner.setUpdatedBy(actor.getId());

        return mapToResponse(learnerRepository.save(learner));
    }


    public BulkUploadResponse bulkUpload(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new MalformedCsvException("No file was provided or the file is empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".csv")) {
            throw new MalformedCsvException("Only .csv files are accepted");
        }
        String contentType = file.getContentType();
        if (contentType != null
                && !contentType.equalsIgnoreCase("text/csv")
                && !contentType.equalsIgnoreCase("application/vnd.ms-excel")
                && !contentType.equalsIgnoreCase("application/octet-stream")) {
            throw new MalformedCsvException("Invalid content type: " + contentType);
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {  // e.g. 5 * 1024 * 1024 (5 MB)
            throw new MalformedCsvException("File exceeds the maximum allowed size of 5 MB");
        }

        CsvParseResult<LearnerCsvRow> parsed = csvParserService.parse(file, LearnerCsvRow.class);

        List<CsvRowError> errors = new ArrayList<>(parsed.errors());
        int savedCount = 0;

        if (parsed.validRows().isEmpty() && errors.isEmpty()) {
            throw new MalformedCsvException("CSV file contains no data rows");
        }

        List<ParsedValid> fieldValid = new ArrayList<>();
        for (var row : parsed.validRows()) {
            LearnerCsvRow r = row.data();
            boolean rowOk = true;

            String fullName = r.getFullName() == null ? null : r.getFullName().strip();
            if (fullName == null || fullName.isBlank()) {
                errors.add(new CsvRowError(row.lineNumber(), "FULL_NAME", "V3", "FULL_NAME is required"));
                rowOk = false;
            } else if (fullName.length() > 255) {
                errors.add(new CsvRowError(row.lineNumber(), "FULL_NAME",
                        "Full name must not exceed 255 characters"));
                rowOk = false;
            }

            String rawEmail = r.getEmail() == null ? null : r.getEmail().strip();
            if (rawEmail == null || rawEmail.isBlank()) {
                errors.add(new CsvRowError(row.lineNumber(), "EMAIL", "V3", "EMAIL is required"));
                rowOk = false;
            } else if (!EMAIL_PATTERN.matcher(rawEmail).matches()) {
                errors.add(new CsvRowError(row.lineNumber(), "EMAIL", "V8",
                        "'" + rawEmail + "' is not a valid email address"));
                rowOk = false;
            } else if (rawEmail.length() > 254) {
                errors.add(new CsvRowError(row.lineNumber(), "EMAIL",
                        "Email must not exceed 254 characters"));
                rowOk = false;
            }


            String cohortName = r.getCohortName() == null ? null : r.getCohortName().strip();
            if (cohortName == null || cohortName.isBlank()) {
                errors.add(new CsvRowError(row.lineNumber(), "COHORT_NAME", "V3",
                        "COHORT_NAME is required"));
                rowOk = false;
            }

            String specName = r.getSpecializationName() == null
                    ? null : r.getSpecializationName().strip();
            if (specName == null || specName.isBlank()) {
                errors.add(new CsvRowError(row.lineNumber(), "SPECIALIZATION_NAME", "V3",
                        "SPECIALIZATION_NAME is required"));
                rowOk = false;
            }

            if (rowOk) {
                r.setFullName(fullName);
                r.setEmail(rawEmail);
                r.setCohortName(cohortName);
                r.setSpecializationName(specName);
                fieldValid.add(new ParsedValid(row.lineNumber(), r));
            }
        }

        Map<String, List<Long>> emailLines = new LinkedHashMap<>();
        for (ParsedValid v : fieldValid) {
            String key = v.row().getEmail().toLowerCase(Locale.ROOT);
            emailLines.computeIfAbsent(key, k -> new ArrayList<>()).add(v.lineNumber());
        }
        Set<Long> duplicateLines = new HashSet<>();
        for (Map.Entry<String, List<Long>> entry : emailLines.entrySet()) {
            if (entry.getValue().size() > 1) {
                entry.getValue().forEach(duplicateLines::add);
                entry.getValue().forEach(ln -> errors.add(new CsvRowError(
                        ln, "EMAIL", "V16",
                        "Duplicate email within this file: " + entry.getKey())));
            }
        }

        User actor = currentUser();

        Set<String> inFileEmails = fieldValid.stream()
                .filter(v -> !duplicateLines.contains(v.lineNumber()))
                .map(v -> v.row().getEmail().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Set<String> existingEmails = learnerRepository
                .findExistingEmails(inFileEmails);

        for (ParsedValid v : fieldValid) {
            if (duplicateLines.contains(v.lineNumber())) {
                continue;
            }

            // A single bad row must never abort the whole upload: any failure is
            // captured as a row-level error so the rest of the file still imports.
            try {
                if (importRow(v, existingEmails, actor, errors)) {
                    savedCount++;
                }
            } catch (DataIntegrityViolationException ex) {
                LOG.debug("DB constraint violation saving learner at row {}: {}",
                        v.lineNumber(), ex.getMessage(), ex);
                errors.add(toDbRowError(v.lineNumber(), ex));
            } catch (DataAccessException ex) {
                LOG.error("Database error saving learner at row {}: {}",
                        v.lineNumber(), ex.getMostSpecificCause().getMessage(), ex);
                errors.add(new CsvRowError(v.lineNumber(), null,
                        "Failed to save to the database: " + ex.getMostSpecificCause().getMessage()));
            } catch (RuntimeException ex) {
                LOG.error("Unexpected error processing learner at row {}: {}",
                        v.lineNumber(), ex.getMessage(), ex);
                errors.add(new CsvRowError(v.lineNumber(), null,
                        "Failed to process row: " + ex.getMessage()));
            }
        }

        return BulkUploadResponse.builder()
                .acceptedCount(savedCount)
                .rejectedCount(errors.size())
                .errors(errors)
                .build();
    }

    // ── Template download ─────────────────────────────────────────────────────

    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"learner_upload_template.csv\"");
        csvWriterService.writeTemplate(response.getWriter(), LearnerCsvRow.class);
    }

    // ── AC-3: Paginated + filtered list ──────────────────────────────────────

    public PagedResponse<LearnerResponse> getLearners(
            UUID cohortId,
            UUID specializationId,
            LearnerStatus status,
            String search,
            Pageable pageable) {

        Specification<Learner> spec =
            LearnerSpecifications.withFilters(cohortId, specializationId, status, search);
        Page<LearnerResponse> page =
            learnerRepository.findAll(spec, pageable).map(this::mapToResponse);
        return PagedResponse.of(page);
    }

    // ── AC-3: Fetch one ───────────────────────────────────────────────────────

    public LearnerResponse getLearnerById(UUID id) {
        return mapToResponse(findOrThrow(id));
    }

    // ── AC-3: Update ──────────────────────────────────────────────────────────

    public LearnerResponse updateLearner(UUID id, UpdateLearnerRequest request) {
        Learner learner = findOrThrow(id);

        Cohort cohort = cohortRepository.findById(request.getCohortId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Cohort with id '" + request.getCohortId() + "' not found"));

        if (cohort.isLocked()) {
            throw new UnprocessableEntityException(
                "Cohort '" + cohort.getName() + "' is locked — learners cannot be modified");
        }

        Specialization specialization = specializationRepository
            .findByIdAndCohortId(request.getSpecializationId(), request.getCohortId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Specialization with id '" + request.getSpecializationId()
                + "' not found in cohort '" + cohort.getName() + "'"));

        User actor = currentUser();
        learner.setFullName(request.getFullName());
        learner.setCohort(cohort);
        learner.setSpecialization(specialization);
        learner.setUpdatedBy(actor.getId());

        return mapToResponse(learnerRepository.save(learner));
    }

    // ── AC-3: Archive / reactivate ────────────────────────────────────────────

    public LearnerResponse updateLearnerStatus(UUID id, UpdateLearnerStatusRequest request) {
        Learner learner = findOrThrow(id);
        learner.setStatus(request.getStatus());
        learner.setUpdatedBy(currentUser().getId());
        return mapToResponse(learnerRepository.save(learner));
    }

    // ── AC-3: Delete ──────────────────────────────────────────────────────────

    public void deleteLearner(UUID id) {
        Learner learner = findOrThrow(id);

        if (learner.getCohort().isLocked()) {
            throw new UnprocessableEntityException(
                "Cohort '" + learner.getCohort().getName() + "' is locked — learners cannot be deleted");
        }

        if (labResultRepository.existsByLearnerId(id)) {
            throw new DuplicateResourceException(
                "Learner has associated lab results. Archive the learner instead.");
        }

        learnerRepository.delete(learner);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Learner findOrThrow(UUID id) {
        return learnerRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Learner with id '" + id + "' not found"));
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private LearnerResponse mapToResponse(Learner learner) {
        return LearnerResponse.builder()
            .id(learner.getId())
            .fullName(learner.getFullName())
            .email(learner.getEmail())
            .cohortId(learner.getCohort().getId())
            .cohortName(learner.getCohort().getName())
            .specializationId(learner.getSpecialization().getId())
            .specializationName(learner.getSpecialization().getName())
            .status(learner.getStatus())
            .createdAt(learner.getCreatedAt())
            .updatedAt(learner.getUpdatedAt())
            .build();
    }

    /**
     * Resolve a single field-valid row's cohort + specialization and persist it.
     *
     * @return {@code true} if the learner was saved; {@code false} if the row was rejected with a
     *         row-level error (added to {@code errors}). Database/transaction exceptions are left to
     *         propagate so the caller can record them per row without aborting the batch.
     */
    private boolean importRow(ParsedValid v, Set<String> existingEmails,
            User actor, List<CsvRowError> errors) {
        LearnerCsvRow r = v.row();
        long line = v.lineNumber();
        String email = r.getEmail().toLowerCase(Locale.ROOT);

        if (existingEmails.contains(email)) {
            errors.add(new CsvRowError(line, "EMAIL", "Email '" + email + "' already exists"));
            return false;
        }

        Cohort cohort;
        try {
            Optional<Cohort> cohortOpt = cohortRepository.findByNameIgnoreCase(r.getCohortName());
            if (cohortOpt.isEmpty()) {
                errors.add(new CsvRowError(line, "COHORT_NAME",
                        "Cohort '" + r.getCohortName() + "' not found"));
                return false;
            }
            cohort = cohortOpt.get();
            if (cohort.isLocked()) {
                errors.add(new CsvRowError(line, "COHORT_NAME",
                    "Cohort '" + r.getCohortName() + "' is locked — learners cannot be added"));
                return false;
            }
        } catch (IncorrectResultSizeDataAccessException ex) {
            errors.add(new CsvRowError(line, "COHORT_NAME",
                    "Cohort name '" + r.getCohortName() + "' is ambiguous — multiple cohorts match "
                            + "(check for case or spacing variants)"));
            return false;
        }

        Specialization specialization;
        try {
            Optional<Specialization> specOpt = specializationRepository
                    .findByCohortIdAndNameIgnoreCase(cohort.getId(), r.getSpecializationName());
            if (specOpt.isEmpty()) {
                errors.add(new CsvRowError(line, "SPECIALIZATION_NAME",
                        "Specialization '" + r.getSpecializationName()
                                + "' not found in cohort '" + r.getCohortName() + "'"));
                return false;
            }
            specialization = specOpt.get();
        } catch (IncorrectResultSizeDataAccessException ex) {
            errors.add(new CsvRowError(line, "SPECIALIZATION_NAME",
                    "Specialization name '" + r.getSpecializationName() + "' is ambiguous in cohort '"
                            + r.getCohortName() + "' — multiple match"));
            return false;
        }

        Learner learner = Learner.builder()
                .fullName(r.getFullName())
                .email(email)
                .cohort(cohort)
                .specialization(specialization)
                .status(LearnerStatus.ACTIVE)
                .build();
        learner.setCreatedBy(actor.getId());
        learner.setUpdatedBy(actor.getId());

        learnerRepository.save(learner);
        return true;
    }

    private static CsvRowError toDbRowError(long lineNumber, DataIntegrityViolationException ex) {
        String cause = ex.getMostSpecificCause().getMessage();
        if (cause != null && cause.contains("duplicate key")) {
            Matcher m = DUPLICATE_KEY_DETAIL.matcher(cause);
            if (m.find()) {
                String field = m.group(1).toUpperCase(Locale.ROOT);
                String value = m.group(2);
                return new CsvRowError(lineNumber, field,
                    "'" + value + "' already exists — " + field + " must be unique");
            }
            return new CsvRowError(lineNumber, null,
                "A duplicate value violates a unique constraint");
        }
        return new CsvRowError(lineNumber, null,
            "Row could not be saved due to a database constraint violation");
    }

    private record ParsedValid(long lineNumber, LearnerCsvRow row) {
    }
}
