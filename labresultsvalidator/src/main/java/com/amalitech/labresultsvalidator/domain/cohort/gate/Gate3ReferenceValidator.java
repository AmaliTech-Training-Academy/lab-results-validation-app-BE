package com.amalitech.labresultsvalidator.domain.cohort.gate;

import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class Gate3ReferenceValidator {

    private static final Logger LOG = LoggerFactory.getLogger(Gate3ReferenceValidator.class);
    private static final long MAX_ENTRY_SIZE = 20L * 1024 * 1024;

    private final GraphDriveService graphDriveService;
    private final SharePointProperties sharePointProperties;

    public Gate3ReferenceValidator(GraphDriveService graphDriveService, SharePointProperties sharePointProperties) {
        this.graphDriveService = graphDriveService;
        this.sharePointProperties = sharePointProperties;
    }

    public Gate3Result validate(String driveId, String referenceFolderItemId) {
        List<DriveItemInfo> refChildren;
        try {
            refChildren = graphDriveService.listChildren(driveId, referenceFolderItemId);
        } catch (GraphAccessException ex) {
            return new Gate3Result(
                GateResult.fail(null, null, "G3-ACCESS",
                    "Cannot list reference folder contents."),
                null
            );
        }

        // Key by lowercase name so all lookups are case-insensitive.
        Map<String, DriveItemInfo> childByName = refChildren.stream()
            .collect(Collectors.toMap(
                d -> d.name().toLowerCase(Locale.ROOT),
                Function.identity(),
                (a, b) -> a
            ));

        LOG.info("[gate3] files found in reference folder: {}", childByName.keySet());

        // expectedRefFileNames() contains only the 4 required files (quiz reference is optional).
        List<String> expected = sharePointProperties.expectedRefFileNames();
        List<GateError> missingErrors = new ArrayList<>();
        for (String fname : expected) {
            if (!childByName.containsKey(fname.toLowerCase(Locale.ROOT))) {
                missingErrors.add(new GateError(fname, null, "G3-MISSING-FILE",
                    "Required reference file '" + fname + "' not found in the reference folder. "
                        + "Found: " + childByName.keySet()));
            }
        }
        if (!missingErrors.isEmpty()) {
            return new Gate3Result(GateResult.fail(missingErrors), null);
        }

        String specsFile = sharePointProperties.refFiles().specializations();
        String modulesFile = sharePointProperties.refFiles().modules();
        String labsFile = sharePointProperties.refFiles().labs();
        String learnersFile = sharePointProperties.refFiles().learners();
        String quizRefFile = sharePointProperties.refFiles().instructors();

        Map<String, byte[]> fileBytes = new HashMap<>();
        List<GateError> downloadErrors = new ArrayList<>();
        for (String fname : expected) {
            DriveItemInfo info = childByName.get(fname.toLowerCase(Locale.ROOT));
            try {
                fileBytes.put(fname, graphDriveService.downloadFile(driveId, info.itemId()));
            } catch (GraphAccessException ex) {
                downloadErrors.add(new GateError(fname, null, "G3-DOWNLOAD-FAIL",
                    "Could not download reference file '" + fname + "': " + ex.getMessage()));
            }
        }
        if (!downloadErrors.isEmpty()) {
            return new Gate3Result(GateResult.fail(downloadErrors), null);
        }

        List<GateError> allErrors = new ArrayList<>();

        List<ValidatedReferenceBundle.SpecializationRow> specializations =
            validateSpecializations(specsFile, fileBytes.get(specsFile), allErrors);

        Set<String> validSpecIds = specializations.stream()
            .map(ValidatedReferenceBundle.SpecializationRow::specializationId)
            .collect(Collectors.toSet());

        // Spec names are used to cross-reference the trainee database (which links by name).
        Set<String> validSpecNamesLower = specializations.stream()
            .map(r -> r.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());

        List<ValidatedReferenceBundle.ModuleRow> modules =
            validateModules(modulesFile, fileBytes.get(modulesFile), validSpecIds, allErrors);

        Set<String> validModuleIds = modules.stream()
            .map(ValidatedReferenceBundle.ModuleRow::moduleId)
            .collect(Collectors.toSet());

        List<ValidatedReferenceBundle.LabRow> labs =
            validateLabs(labsFile, fileBytes.get(labsFile), validModuleIds, allErrors);

        List<ValidatedReferenceBundle.LearnerRow> learners =
            validateLearners(learnersFile, fileBytes.get(learnersFile), validSpecNamesLower, allErrors);

        if (!allErrors.isEmpty()) {
            return new Gate3Result(GateResult.fail(allErrors), null);
        }

        boolean quizReferencePresent = childByName.containsKey(quizRefFile.toLowerCase(Locale.ROOT));
        if (quizReferencePresent) {
            LOG.info("[gate3] optional quiz reference file '{}' found", quizRefFile);
        } else {
            LOG.info("[gate3] optional quiz reference file '{}' not present", quizRefFile);
        }

        return new Gate3Result(GateResult.pass(),
            new ValidatedReferenceBundle(specializations, modules, labs, learners, quizReferencePresent));
    }

    // Columns: specializationid, specialization
    private List<ValidatedReferenceBundle.SpecializationRow> validateSpecializations(
            String fileName, byte[] bytes, List<GateError> errors) {
        List<ValidatedReferenceBundle.SpecializationRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) return rows;

        Map<String, Integer> headers = readHeaders(sheet);
        List<GateError> colErrors = checkRequiredColumns(fileName, headers, "specializationid", "specialization");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        Set<String> seenIds = new HashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) continue;
            int rowNum = i + 1;
            String specId = getCellString(row, headers.get("specializationid"));
            String name = getCellString(row, headers.get("specialization"));

            if (specId == null || specId.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-SPEC-ID",
                    "Specialization ID is blank."));
                continue;
            }
            if (name == null || name.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-SPEC-NAME",
                    "Specialization name is blank."));
                continue;
            }
            if (!seenIds.add(specId)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-DUP-SPEC-ID",
                    "Duplicate specialization ID '" + specId + "'."));
                continue;
            }
            rows.add(new ValidatedReferenceBundle.SpecializationRow(specId, name));
        }
        return rows;
    }

    // Columns: specializationid, moduleid, module name, phase
    private List<ValidatedReferenceBundle.ModuleRow> validateModules(
            String fileName, byte[] bytes, Set<String> validSpecIds, List<GateError> errors) {
        List<ValidatedReferenceBundle.ModuleRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) return rows;

        Map<String, Integer> headers = readHeaders(sheet);
        List<GateError> colErrors = checkRequiredColumns(
            fileName, headers, "specializationid", "moduleid", "module name", "phase");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) continue;
            int rowNum = i + 1;
            String specId = getCellString(row, headers.get("specializationid"));
            String moduleId = getCellString(row, headers.get("moduleid"));
            String name = getCellString(row, headers.get("module name"));
            String phaseStr = getCellString(row, headers.get("phase"));

            if (moduleId == null || moduleId.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-MODULE-ID",
                    "Module ID is blank."));
                continue;
            }
            if (name == null || name.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-MODULE-NAME",
                    "Module name is blank."));
                continue;
            }
            if (phaseStr == null || phaseStr.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-PHASE",
                    "Phase is blank."));
                continue;
            }
            if (specId == null || !validSpecIds.contains(specId)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-UNKNOWN-SPEC-ID",
                    "Specialization ID '" + specId + "' not found in Specializations file."));
                continue;
            }
            rows.add(new ValidatedReferenceBundle.ModuleRow(moduleId, name, phaseStr, specId));
        }
        return rows;
    }

    // Columns: moduleid, module name, assessmentid, lab title
    private List<ValidatedReferenceBundle.LabRow> validateLabs(
            String fileName, byte[] bytes, Set<String> validModuleIds, List<GateError> errors) {
        List<ValidatedReferenceBundle.LabRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) return rows;

        Map<String, Integer> headers = readHeaders(sheet);
        List<GateError> colErrors = checkRequiredColumns(
            fileName, headers, "moduleid", "assessmentid", "lab title");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) continue;
            int rowNum = i + 1;
            String moduleId = getCellString(row, headers.get("moduleid"));
            String assessmentId = getCellString(row, headers.get("assessmentid"));
            String labTitle = getCellString(row, headers.get("lab title"));

            if (labTitle == null || labTitle.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-LAB-TITLE",
                    "Lab title is blank."));
                continue;
            }
            if (assessmentId == null || assessmentId.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-ASSESSMENT-ID",
                    "Assessment ID is blank."));
                continue;
            }
            if (moduleId == null || !validModuleIds.contains(moduleId)) {
                // Module may be absent from this cohort's Module Setup — skip rather than fail.
                LOG.warn("[gate3] {} row {} — lab '{}' references unknown moduleId '{}', skipping",
                    fileName, rowNum, labTitle, moduleId);
                continue;
            }
            rows.add(new ValidatedReferenceBundle.LabRow(assessmentId, labTitle, moduleId));
        }
        return rows;
    }

    // Columns: amalitech email, full name, specialization
    private List<ValidatedReferenceBundle.LearnerRow> validateLearners(
            String fileName, byte[] bytes, Set<String> validSpecNamesLower, List<GateError> errors) {
        List<ValidatedReferenceBundle.LearnerRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) return rows;

        Map<String, Integer> headers = readHeaders(sheet);
        List<GateError> colErrors = checkRequiredColumns(
            fileName, headers, "amalitech email", "full name", "specialization");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        Set<String> seenEmails = new HashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) continue;
            int rowNum = i + 1;
            String email = getCellString(row, headers.get("amalitech email"));
            String fullName = getCellString(row, headers.get("full name"));
            String specialization = getCellString(row, headers.get("specialization"));

            boolean rowHasError = false;

            if (email == null || email.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-TRAINEE-EMAIL",
                    "Email is blank."));
                rowHasError = true;
            } else if (!seenEmails.add(email.toLowerCase(Locale.ROOT))) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-DUP-TRAINEE-EMAIL",
                    "Duplicate email '" + email + "'."));
                rowHasError = true;
            }

            if (!matchesAnySpecName(specialization, validSpecNamesLower)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-UNKNOWN-SPEC-NAME",
                    "Specialization '" + specialization + "' does not match any entry in Specializations file."));
                rowHasError = true;
            }

            if (!rowHasError) {
                rows.add(new ValidatedReferenceBundle.LearnerRow(email, fullName, specialization));
            }
        }
        return rows;
    }

    // Passes if the trainee value contains any known spec name or vice-versa (case-insensitive).
    private boolean matchesAnySpecName(String traineeSpec, Set<String> validSpecNamesLower) {
        if (traineeSpec == null || traineeSpec.isBlank()) return false;
        String key = traineeSpec.toLowerCase(Locale.ROOT);
        for (String specName : validSpecNamesLower) {
            if (key.contains(specName) || specName.contains(key)) return true;
        }
        return false;
    }

    private Sheet openFirstSheet(String fileName, byte[] bytes, List<GateError> errors) {
        ZipSecureFile.setMinInflateRatio(0);
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_SIZE);
        try {
            Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes));
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) {
                errors.add(new GateError(fileName, null, "G3-EMPTY-WORKBOOK",
                    "Workbook '" + fileName + "' has no sheets."));
            }
            return sheet;
        } catch (IOException ex) {
            LOG.warn("Failed to parse workbook {}: {}", fileName, ex.getMessage());
            errors.add(new GateError(fileName, null, "G3-PARSE-FAIL",
                "Could not parse workbook '" + fileName + "': " + ex.getMessage()));
            return null;
        } catch (Exception ex) {
            LOG.warn("Unexpected error parsing workbook {}: {}", fileName, ex.getMessage());
            errors.add(new GateError(fileName, null, "G3-PARSE-FAIL",
                "Unexpected error reading '" + fileName + "': " + ex.getMessage()));
            return null;
        }
    }

    // Headers are stored lowercase so all column name checks are case-insensitive.
    private Map<String, Integer> readHeaders(Sheet sheet) {
        Map<String, Integer> headers = new HashMap<>();
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) return headers;
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null) {
                String val = cell.getStringCellValue();
                if (val != null && !val.isBlank()) {
                    headers.put(val.trim().toLowerCase(Locale.ROOT), c);
                }
            }
        }
        return headers;
    }

    private List<GateError> checkRequiredColumns(
            String fileName, Map<String, Integer> headers, String... required) {
        List<GateError> errors = new ArrayList<>();
        for (String col : required) {
            if (!headers.containsKey(col)) {
                errors.add(new GateError(fileName, null, "G3-MISSING-COLUMN",
                    "Required column '" + col + "' not found in '" + fileName + "'."));
            }
        }
        return errors;
    }

    private boolean isBlankRow(Row row) {
        if (row == null) return true;
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String val = getCellString(row, c);
                if (val != null && !val.isBlank()) return false;
            }
        }
        return true;
    }

    private String getCellString(Row row, Integer colIndex) {
        if (row == null || colIndex == null) return null;
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d)) yield String.valueOf((long) d);
                yield String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCachedFormulaResultType() == CellType.STRING
                ? cell.getStringCellValue().trim()
                : String.valueOf(cell.getNumericCellValue());
            default -> null;
        };
    }
}
