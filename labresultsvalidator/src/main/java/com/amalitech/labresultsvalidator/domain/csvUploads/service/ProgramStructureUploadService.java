package com.amalitech.labresultsvalidator.domain.csvUploads.service;

import com.amalitech.labresultsvalidator.common.csv.CsvParseResult;
import com.amalitech.labresultsvalidator.common.csv.CsvParserService;
import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import com.amalitech.labresultsvalidator.common.csv.CsvWriterService;
import com.amalitech.labresultsvalidator.common.csv.ParsedRow;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.csvUploads.dto.ProgramStructureCsvRow;
import com.amalitech.labresultsvalidator.domain.csvUploads.dto.ProgramStructureUploadResponse;
import com.amalitech.labresultsvalidator.domain.lab.entity.Lab;
import com.amalitech.labresultsvalidator.domain.lab.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import com.amalitech.labresultsvalidator.domain.module.repository.ModuleRepository;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.specialization.repository.SpecializationRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProgramStructureUploadService {

    private final CsvParserService csvParserService;
    private final CsvWriterService csvWriterService;
    private final CohortRepository cohortRepository;
    private final SpecializationRepository specializationRepository;
    private final ModuleRepository moduleRepository;
    private final LabRepository labRepository;

    @Transactional
    public ProgramStructureUploadResponse upload(MultipartFile file) {
        CsvParseResult<ProgramStructureCsvRow> parsed = csvParserService.parse(file, ProgramStructureCsvRow.class);
        List<CsvRowError> errors = new ArrayList<>(parsed.errors());
        if (!errors.isEmpty()) {
            return errorResponse(errors);
        }

        if (parsed.validRows().isEmpty()) {
            throw new IllegalArgumentException("CSV file contains no data rows");
        }

        errors.addAll(validateFields(parsed.validRows()));
        if (!errors.isEmpty()) {
            return errorResponse(errors);
        }

        Map<String, Optional<Cohort>> cohortCache = buildCohortCache(parsed.validRows());
        errors.addAll(validateCohortsExist(parsed.validRows(), cohortCache));
        if (!errors.isEmpty()) {
            return errorResponse(errors);
        }

        errors.addAll(validateSpecCodeConsistency(parsed.validRows()));
        errors.addAll(validateNoDuplicateLabs(parsed.validRows()));
        if (!errors.isEmpty()) {
            return errorResponse(errors);
        }

        errors.addAll(validateSpecializationsNew(parsed.validRows(), cohortCache));
        if (!errors.isEmpty()) {
            return errorResponse(errors);
        }

        return persist(parsed.validRows(), cohortCache);
    }

    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"program_structure_upload_template.csv\"");
        csvWriterService.writeTemplate(response.getWriter(), ProgramStructureCsvRow.class);
    }

    private List<CsvRowError> validateFields(List<ParsedRow<ProgramStructureCsvRow>> rows) {
        List<CsvRowError> errors = new ArrayList<>();
        for (ParsedRow<ProgramStructureCsvRow> row : rows) {
            ProgramStructureCsvRow r = row.data();
            if (isBlank(r.getCohortName())) {
                errors.add(new CsvRowError(row.lineNumber(), "COHORT_NAME", "Cohort name is required"));
            }
            if (isBlank(r.getSpecializationName())) {
                errors.add(new CsvRowError(row.lineNumber(), "SPECIALIZATION_NAME", "Specialization name is required"));
            }
            if (isBlank(r.getSpecializationCode())) {
                errors.add(new CsvRowError(row.lineNumber(), "SPECIALIZATION_CODE", "Specialization code is required"));
            }
            if (isBlank(r.getModuleName())) {
                errors.add(new CsvRowError(row.lineNumber(), "MODULE_NAME", "Module name is required"));
            }
            if (isBlank(r.getLabTitle())) {
                errors.add(new CsvRowError(row.lineNumber(), "LAB_TITLE", "Lab title is required"));
            }
            validateMaxScore(row, r, errors);
        }
        return errors;
    }

    private void validateMaxScore(ParsedRow<ProgramStructureCsvRow> row, ProgramStructureCsvRow r,
                                  List<CsvRowError> errors) {
        if (isBlank(r.getMaxScore())) {
            errors.add(new CsvRowError(row.lineNumber(), "MAX_SCORE", "Max score is required"));
            return;
        }
        try {
            BigDecimal score = new BigDecimal(r.getMaxScore().trim());
            if (score.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(new CsvRowError(row.lineNumber(), "MAX_SCORE", "Max score must be greater than zero"));
            }
        } catch (NumberFormatException e) {
            errors.add(new CsvRowError(row.lineNumber(), "MAX_SCORE", "Max score must be a valid number"));
        }
    }

    private Map<String, Optional<Cohort>> buildCohortCache(List<ParsedRow<ProgramStructureCsvRow>> rows) {
        Map<String, Optional<Cohort>> cache = new HashMap<>();
        for (ParsedRow<ProgramStructureCsvRow> row : rows) {
            cache.computeIfAbsent(row.data().getCohortName().trim(), cohortRepository::findByNameIgnoreCase);
        }
        return cache;
    }

    private List<CsvRowError> validateCohortsExist(List<ParsedRow<ProgramStructureCsvRow>> rows,
                                                    Map<String, Optional<Cohort>> cohortCache) {
        List<CsvRowError> errors = new ArrayList<>();
        for (ParsedRow<ProgramStructureCsvRow> row : rows) {
            String name = row.data().getCohortName().trim();
            if (cohortCache.get(name).isEmpty()) {
                errors.add(new CsvRowError(row.lineNumber(), "COHORT_NAME",
                    "Cohort '" + name + "' not found — create the cohort first"));
            }
        }
        return errors;
    }

    private List<CsvRowError> validateSpecCodeConsistency(List<ParsedRow<ProgramStructureCsvRow>> rows) {
        List<CsvRowError> errors = new ArrayList<>();
        Map<String, String> specKeyToCode = new HashMap<>();
        for (ParsedRow<ProgramStructureCsvRow> row : rows) {
            ProgramStructureCsvRow r = row.data();
            String code = r.getSpecializationCode().trim();
            String existing = specKeyToCode.get(specKey(r));
            if (existing == null) {
                specKeyToCode.put(specKey(r), code);
            } else if (!existing.equalsIgnoreCase(code)) {
                errors.add(new CsvRowError(row.lineNumber(), "SPECIALIZATION_CODE",
                    "Inconsistent code for specialization '" + r.getSpecializationName()
                    + "': '" + existing + "' vs '" + code + "'"));
            }
        }
        return errors;
    }

    private List<CsvRowError> validateNoDuplicateLabs(List<ParsedRow<ProgramStructureCsvRow>> rows) {
        List<CsvRowError> errors = new ArrayList<>();
        Map<String, Long> seen = new LinkedHashMap<>();
        for (ParsedRow<ProgramStructureCsvRow> row : rows) {
            ProgramStructureCsvRow r = row.data();
            String key = labKey(r);
            if (seen.containsKey(key)) {
                errors.add(new CsvRowError(row.lineNumber(), "LAB_TITLE",
                    "Duplicate lab '" + r.getLabTitle() + "' in module '" + r.getModuleName() + "'"));
            } else {
                seen.put(key, row.lineNumber());
            }
        }
        return errors;
    }

    private List<CsvRowError> validateSpecializationsNew(List<ParsedRow<ProgramStructureCsvRow>> rows,
                                                          Map<String, Optional<Cohort>> cohortCache) {
        List<CsvRowError> errors = new ArrayList<>();
        Map<String, Boolean> checked = new HashMap<>();
        for (ParsedRow<ProgramStructureCsvRow> row : rows) {
            ProgramStructureCsvRow r = row.data();
            String key = specKey(r);
            if (checked.containsKey(key)) {
                continue;
            }
            Cohort cohort = cohortCache.get(r.getCohortName().trim()).get();
            boolean exists = specializationRepository.existsByCohortIdAndName(
                cohort.getId(), r.getSpecializationName().trim());
            checked.put(key, exists);
            if (exists) {
                errors.add(new CsvRowError(row.lineNumber(), "SPECIALIZATION_NAME",
                    "Specialization '" + r.getSpecializationName()
                    + "' already exists in cohort '" + r.getCohortName() + "'"));
            }
        }
        return errors;
    }

    private ProgramStructureUploadResponse persist(List<ParsedRow<ProgramStructureCsvRow>> rows,
                                                    Map<String, Optional<Cohort>> cohortCache) {
        User actor = currentUser();
        Map<String, Specialization> specMap = new LinkedHashMap<>();
        Map<String, Module> moduleMap = new LinkedHashMap<>();
        Map<String, Integer> specModuleSequence = new HashMap<>();
        List<Lab> allLabs = new ArrayList<>();

        for (ParsedRow<ProgramStructureCsvRow> row : rows) {
            ProgramStructureCsvRow r = row.data();
            Cohort cohort = cohortCache.get(r.getCohortName().trim()).get();

            Specialization spec = specMap.computeIfAbsent(specKey(r), k -> {
                Specialization s = Specialization.builder()
                    .cohort(cohort)
                    .name(r.getSpecializationName().trim())
                    .code(r.getSpecializationCode().trim())
                    .build();
                s.setCreatedBy(actor.getId());
                s.setUpdatedBy(actor.getId());
                return s;
            });

            Module module = moduleMap.computeIfAbsent(moduleKey(r), k -> {
                int sequence = specModuleSequence.merge(specKey(r), 1, Integer::sum);
                Module m = Module.builder()
                    .specialization(spec)
                    .name(r.getModuleName().trim())
                    .sequence(sequence)
                    .build();
                m.setCreatedBy(actor.getId());
                m.setUpdatedBy(actor.getId());
                return m;
            });

            Lab lab = Lab.builder()
                .module(module)
                .title(r.getLabTitle().trim())
                .maxScore(new BigDecimal(r.getMaxScore().trim()))
                .build();
            lab.setCreatedBy(actor.getId());
            lab.setUpdatedBy(actor.getId());
            allLabs.add(lab);
        }

        specializationRepository.saveAll(specMap.values());
        moduleRepository.saveAll(moduleMap.values());
        labRepository.saveAll(allLabs);

        return ProgramStructureUploadResponse.builder()
            .specializationsCreated(specMap.size())
            .modulesCreated(moduleMap.size())
            .labsCreated(allLabs.size())
            .errors(List.of())
            .build();
    }

    private static String specKey(ProgramStructureCsvRow r) {
        return (r.getCohortName() + "|" + r.getSpecializationName()).toLowerCase();
    }

    private static String moduleKey(ProgramStructureCsvRow r) {
        return (r.getCohortName() + "|" + r.getSpecializationName() + "|" + r.getModuleName()).toLowerCase();
    }

    private static String labKey(ProgramStructureCsvRow r) {
        return (r.getCohortName() + "|" + r.getSpecializationName()
            + "|" + r.getModuleName() + "|" + r.getLabTitle()).toLowerCase();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private ProgramStructureUploadResponse errorResponse(List<CsvRowError> errors) {
        return ProgramStructureUploadResponse.builder()
            .specializationsCreated(0)
            .modulesCreated(0)
            .labsCreated(0)
            .errors(errors)
            .build();
    }
}