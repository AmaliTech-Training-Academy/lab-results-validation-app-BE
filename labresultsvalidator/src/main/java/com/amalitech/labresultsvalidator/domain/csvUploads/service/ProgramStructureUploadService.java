package com.amalitech.labresultsvalidator.domain.csvUploads.service;

import com.amalitech.labresultsvalidator.common.csv.CsvParseResult;
import com.amalitech.labresultsvalidator.common.csv.CsvParserService;
import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
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
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
    private final CohortRepository cohortRepository;
    private final SpecializationRepository specializationRepository;
    private final ModuleRepository moduleRepository;
    private final LabRepository labRepository;

    @Transactional
    public ProgramStructureUploadResponse upload(MultipartFile file) {

        // Stage 1: Parse CSV — structural failures (bad format, missing headers) reject everything
        CsvParseResult<ProgramStructureCsvRow> parsed = csvParserService.parse(file, ProgramStructureCsvRow.class);
        List<CsvRowError> errors = new ArrayList<>(parsed.errors());
        if (!errors.isEmpty()) {
            return errorResponse(errors);
        }

        if (parsed.validRows().isEmpty()) {
            throw new IllegalArgumentException("CSV file contains no data rows");
        }

        // Stage 2: Field-level validation
        for (ParsedRow<ProgramStructureCsvRow> row : parsed.validRows()) {
            ProgramStructureCsvRow r = row.data();
            if (r.getCohortName() == null || r.getCohortName().isBlank()) {
                errors.add(new CsvRowError(row.lineNumber(), "COHORT_NAME", "Cohort name is required"));
            }
            if (r.getSpecializationName() == null || r.getSpecializationName().isBlank()) {
                errors.add(new CsvRowError(row.lineNumber(), "SPECIALIZATION_NAME", "Specialization name is required"));
            }
            if (r.getSpecializationCode() == null || r.getSpecializationCode().isBlank()) {
                errors.add(new CsvRowError(row.lineNumber(), "SPECIALIZATION_CODE", "Specialization code is required"));
            }
            if (r.getModuleName() == null || r.getModuleName().isBlank()) {
                errors.add(new CsvRowError(row.lineNumber(), "MODULE_NAME", "Module name is required"));
            }
            if (r.getLabTitle() == null || r.getLabTitle().isBlank()) {
                errors.add(new CsvRowError(row.lineNumber(), "LAB_TITLE", "Lab title is required"));
            }
            if (r.getMaxScore() == null || r.getMaxScore().isBlank()) {
                errors.add(new CsvRowError(row.lineNumber(), "MAX_SCORE", "Max score is required"));
            } else {
                try {
                    BigDecimal score = new BigDecimal(r.getMaxScore().trim());
                    if (score.compareTo(BigDecimal.ZERO) <= 0) {
                        errors.add(new CsvRowError(row.lineNumber(), "MAX_SCORE", "Max score must be greater than zero"));
                    }
                } catch (NumberFormatException e) {
                    errors.add(new CsvRowError(row.lineNumber(), "MAX_SCORE", "Max score must be a valid number"));
                }
            }
        }
        if (!errors.isEmpty()) {
            return errorResponse(errors);
        }

        // Stage 3: Cohort existence — cohort must already exist before uploading structure
        Map<String, Optional<Cohort>> cohortCache = new HashMap<>();
        for (ParsedRow<ProgramStructureCsvRow> row : parsed.validRows()) {
            String name = row.data().getCohortName().trim();
            cohortCache.computeIfAbsent(name, cohortRepository::findByNameIgnoreCase);
        }
        for (ParsedRow<ProgramStructureCsvRow> row : parsed.validRows()) {
            String name = row.data().getCohortName().trim();
            if (cohortCache.get(name).isEmpty()) {
                errors.add(new CsvRowError(row.lineNumber(), "COHORT_NAME",
                    "Cohort '" + name + "' not found — create the cohort first"));
            }
        }
        if (!errors.isEmpty()) {
            return errorResponse(errors);
        }

        // Stage 4a: Detect inconsistent specialization codes within the file
        // Same specialization name must use the same code on every row
        Map<String, String> specKeyToCode = new HashMap<>();
        for (ParsedRow<ProgramStructureCsvRow> row : parsed.validRows()) {
            ProgramStructureCsvRow r = row.data();
            String specKey = (r.getCohortName() + "|" + r.getSpecializationName()).toLowerCase();
            String code = r.getSpecializationCode().trim();
            String existing = specKeyToCode.get(specKey);
            if (existing == null) {
                specKeyToCode.put(specKey, code);
            } else if (!existing.equalsIgnoreCase(code)) {
                errors.add(new CsvRowError(row.lineNumber(), "SPECIALIZATION_CODE",
                    "Inconsistent code for specialization '" + r.getSpecializationName()
                    + "': '" + existing + "' vs '" + code + "'"));
            }
        }

        // Stage 4b: Detect duplicate labs within the file
        Map<String, Long> seenLabKeys = new LinkedHashMap<>();
        for (ParsedRow<ProgramStructureCsvRow> row : parsed.validRows()) {
            ProgramStructureCsvRow r = row.data();
            String labKey = (r.getCohortName() + "|" + r.getSpecializationName()
                + "|" + r.getModuleName() + "|" + r.getLabTitle()).toLowerCase();
            if (seenLabKeys.containsKey(labKey)) {
                errors.add(new CsvRowError(row.lineNumber(), "LAB_TITLE",
                    "Duplicate lab '" + r.getLabTitle() + "' in module '" + r.getModuleName() + "'"));
            } else {
                seenLabKeys.put(labKey, row.lineNumber());
            }
        }
        if (!errors.isEmpty()) {
            return errorResponse(errors);
        }

        // Stage 5: DB uniqueness — specializations must not already exist in the cohort
        Map<String, Boolean> checkedSpecs = new HashMap<>();
        for (ParsedRow<ProgramStructureCsvRow> row : parsed.validRows()) {
            ProgramStructureCsvRow r = row.data();
            Cohort cohort = cohortCache.get(r.getCohortName().trim()).get();
            String specKey = (r.getCohortName() + "|" + r.getSpecializationName()).toLowerCase();
            if (!checkedSpecs.containsKey(specKey)) {
                boolean exists = specializationRepository.existsByCohortIdAndName(
                    cohort.getId(), r.getSpecializationName().trim());
                checkedSpecs.put(specKey, exists);
                if (exists) {
                    errors.add(new CsvRowError(row.lineNumber(), "SPECIALIZATION_NAME",
                        "Specialization '" + r.getSpecializationName()
                        + "' already exists in cohort '" + r.getCohortName() + "'"));
                }
            }
        }
        if (!errors.isEmpty()) {
            return errorResponse(errors);
        }

        // Stage 6: Build and persist the full hierarchy atomically
        // Order matters: specializations → modules (FK to spec) → labs (FK to module)
        User actor = currentUser();

        Map<String, Specialization> specMap = new LinkedHashMap<>();
        Map<String, Module> moduleMap = new LinkedHashMap<>();
        Map<String, Integer> specModuleSequence = new HashMap<>();
        List<Lab> allLabs = new ArrayList<>();

        for (ParsedRow<ProgramStructureCsvRow> row : parsed.validRows()) {
            ProgramStructureCsvRow r = row.data();
            Cohort cohort = cohortCache.get(r.getCohortName().trim()).get();

            String specKey = (r.getCohortName() + "|" + r.getSpecializationName()).toLowerCase();
            Specialization spec = specMap.computeIfAbsent(specKey, k -> {
                Specialization s = Specialization.builder()
                    .cohort(cohort)
                    .name(r.getSpecializationName().trim())
                    .code(r.getSpecializationCode().trim())
                    .build();
                s.setCreatedBy(actor.getId());
                s.setUpdatedBy(actor.getId());
                return s;
            });

            String moduleKey = (r.getCohortName() + "|" + r.getSpecializationName()
                + "|" + r.getModuleName()).toLowerCase();
            Module module = moduleMap.computeIfAbsent(moduleKey, k -> {
                // Sequence is the order of first appearance within the specialization
                int sequence = specModuleSequence.merge(specKey, 1, Integer::sum);
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