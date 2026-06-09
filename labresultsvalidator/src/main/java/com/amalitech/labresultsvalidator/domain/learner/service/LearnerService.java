package com.amalitech.labresultsvalidator.domain.learner.service;

import com.amalitech.labresultsvalidator.common.csv.CsvParseResult;
import com.amalitech.labresultsvalidator.common.csv.CsvParserService;
import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import com.amalitech.labresultsvalidator.common.csv.CsvWriterService;
import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LearnerService {

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[\\w.+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

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

    // ── AC-2: Bulk CSV upload ─────────────────────────────────────────────────

    @Transactional
    public BulkUploadResponse bulkUpload(MultipartFile file) {
        CsvParseResult<LearnerCsvRow> parsed = csvParserService.parse(file, LearnerCsvRow.class);

        List<CsvRowError> errors = new ArrayList<>(parsed.errors());
        List<Learner> toSave = new ArrayList<>();

        // Stage 2: field-level validation
        List<ParsedValid> fieldValid = new ArrayList<>();
        for (var row : parsed.validRows()) {
            LearnerCsvRow r = row.data();
            boolean rowOk = true;

            if (r.getFullName() == null || r.getFullName().isBlank()) {
                errors.add(new CsvRowError(row.lineNumber(), "FULL_NAME", "Full name is required"));
                rowOk = false;
            }
            if (r.getEmail() == null || r.getEmail().isBlank()) {
                errors.add(new CsvRowError(row.lineNumber(), "EMAIL", "Email is required"));
                rowOk = false;
            } else if (!EMAIL_PATTERN.matcher(r.getEmail().trim()).matches()) {
                errors.add(new CsvRowError(row.lineNumber(), "EMAIL",
                    "'" + r.getEmail() + "' is not a valid email address"));
                rowOk = false;
            }

            if (rowOk) {
                fieldValid.add(new ParsedValid(row.lineNumber(), r));
            }
        }

        // Stage 3a: detect in-file duplicate emails
        Map<String, List<Long>> emailLines = new HashMap<>();
        for (ParsedValid v : fieldValid) {
            String key = v.row().getEmail().trim().toLowerCase();
            emailLines.computeIfAbsent(key, k -> new ArrayList<>()).add(v.lineNumber());
        }
        Set<Long> duplicateLines = new HashSet<>();
        for (Map.Entry<String, List<Long>> entry : emailLines.entrySet()) {
            if (entry.getValue().size() > 1) {
                entry.getValue().forEach(duplicateLines::add);
                entry.getValue().forEach(ln -> errors.add(new CsvRowError(
                    ln, "EMAIL", "Duplicate email within this file: " + entry.getKey())));
            }
        }

        // Stage 3b: referential validation + DB uniqueness
        User actor = currentUser();
        for (ParsedValid v : fieldValid) {
            if (duplicateLines.contains(v.lineNumber())) {
                continue;
            }
            LearnerCsvRow r = v.row();
            String email = r.getEmail().trim().toLowerCase();

            if (learnerRepository.existsByEmailIgnoreCase(email)) {
                errors.add(new CsvRowError(v.lineNumber(), "EMAIL",
                    "Email '" + email + "' already exists"));
                continue;
            }

            Optional<Cohort> cohortOpt =
                cohortRepository.findByNameIgnoreCase(r.getCohortName().trim());
            if (cohortOpt.isEmpty()) {
                errors.add(new CsvRowError(v.lineNumber(), "COHORT_NAME",
                    "Cohort '" + r.getCohortName() + "' not found"));
                continue;
            }

            Optional<Specialization> specOpt = specializationRepository
                .findByCohortIdAndNameIgnoreCase(
                    cohortOpt.get().getId(), r.getSpecializationName().trim());
            if (specOpt.isEmpty()) {
                errors.add(new CsvRowError(v.lineNumber(), "SPECIALIZATION_NAME",
                    "Specialization '" + r.getSpecializationName()
                    + "' not found in cohort '" + r.getCohortName() + "'"));
                continue;
            }

            Learner learner = Learner.builder()
                .fullName(r.getFullName().trim())
                .email(email)
                .cohort(cohortOpt.get())
                .specialization(specOpt.get())
                .status(LearnerStatus.ACTIVE)
                .build();
            learner.setCreatedBy(actor.getId());
            learner.setUpdatedBy(actor.getId());
            toSave.add(learner);
        }

        learnerRepository.saveAll(toSave);

        return BulkUploadResponse.builder()
            .acceptedCount(toSave.size())
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

    private record ParsedValid(long lineNumber, LearnerCsvRow row) {
    }
}
