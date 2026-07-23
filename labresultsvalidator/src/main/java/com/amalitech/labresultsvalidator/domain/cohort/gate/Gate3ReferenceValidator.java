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

        Map<String, DriveItemInfo> childByName = refChildren.stream()
            .collect(Collectors.toMap(DriveItemInfo::name, Function.identity(), (a, b) -> a));

        List<String> expected = sharePointProperties.expectedRefFileNames();
        List<GateError> missingErrors = new ArrayList<>();
        for (String fname : expected) {
            if (!childByName.containsKey(fname)) {
                missingErrors.add(new GateError(fname, null, "G3-MISSING-FILE",
                    "Required reference file '" + fname + "' not found in the reference folder."));
            }
        }
        if (!missingErrors.isEmpty()) {
            return new Gate3Result(GateResult.fail(missingErrors), null);
        }

        String specsFile = sharePointProperties.refFiles().specializations();
        String modulesFile = sharePointProperties.refFiles().modules();
        String labsFile = sharePointProperties.refFiles().labs();
        String learnersFile = sharePointProperties.refFiles().learners();
        String instructorsFile = sharePointProperties.refFiles().instructors();

        Map<String, byte[]> fileBytes = new HashMap<>();
        List<GateError> downloadErrors = new ArrayList<>();
        for (String fname : expected) {
            DriveItemInfo info = childByName.get(fname);
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

        Set<String> specCodes = specializations.stream()
            .map(ValidatedReferenceBundle.SpecializationRow::code)
            .collect(Collectors.toSet());

        List<ValidatedReferenceBundle.ModuleRow> modules =
            validateModules(modulesFile, fileBytes.get(modulesFile), specCodes, allErrors);

        Set<String> moduleCodes = modules.stream()
            .map(ValidatedReferenceBundle.ModuleRow::code)
            .collect(Collectors.toSet());

        List<ValidatedReferenceBundle.LabRow> labs =
            validateLabs(labsFile, fileBytes.get(labsFile), moduleCodes, allErrors);

        List<ValidatedReferenceBundle.LearnerRow> learners =
            validateLearners(learnersFile, fileBytes.get(learnersFile), specCodes, allErrors);

        List<ValidatedReferenceBundle.InstructorContactRow> instructors =
            validateInstructors(instructorsFile, fileBytes.get(instructorsFile), allErrors);

        if (!allErrors.isEmpty()) {
            return new Gate3Result(GateResult.fail(allErrors), null);
        }

        return new Gate3Result(GateResult.pass(),
            new ValidatedReferenceBundle(specializations, modules, labs, learners, instructors));
    }

    private List<ValidatedReferenceBundle.SpecializationRow> validateSpecializations(
            String fileName, byte[] bytes, List<GateError> errors) {
        List<ValidatedReferenceBundle.SpecializationRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) {
            return rows;
        }

        Map<String, Integer> headers = readHeaders(sheet);
        List<GateError> colErrors = checkRequiredColumns(fileName, headers, "Name", "Code");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        Set<String> seenCodes = new HashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) {
                continue;
            }
            String name = getCellString(row, headers.get("Name"));
            String code = getCellString(row, headers.get("Code"));
            int rowNum = i + 1;

            if (name == null || name.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-SPEC-NAME",
                    "Specialization name is blank."));
                continue;
            }
            if (code == null || code.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-SPEC-CODE",
                    "Specialization code is blank."));
                continue;
            }
            if (!seenCodes.add(code)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-DUP-SPEC-CODE",
                    "Duplicate specialization code '" + code + "'."));
                continue;
            }
            rows.add(new ValidatedReferenceBundle.SpecializationRow(name, code));
        }
        return rows;
    }

    private List<ValidatedReferenceBundle.ModuleRow> validateModules(
            String fileName, byte[] bytes, Set<String> validSpecCodes, List<GateError> errors) {
        List<ValidatedReferenceBundle.ModuleRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) {
            return rows;
        }

        Map<String, Integer> headers = readHeaders(sheet);
        List<GateError> colErrors = checkRequiredColumns(
            fileName, headers, "Name", "Code", "Sequence", "Specialization Code");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) {
                continue;
            }
            int rowNum = i + 1;
            String name = getCellString(row, headers.get("Name"));
            String code = getCellString(row, headers.get("Code"));
            String seqStr = getCellString(row, headers.get("Sequence"));
            String specCode = getCellString(row, headers.get("Specialization Code"));

            if (name == null || name.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-MODULE-NAME",
                    "Module name is blank."));
                continue;
            }

            int sequence;
            try {
                sequence = Integer.parseInt(seqStr == null ? "" : seqStr.trim());
                if (sequence <= 0) {
                    throw new NumberFormatException("non-positive");
                }
            } catch (NumberFormatException ex) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-INVALID-SEQUENCE",
                    "Sequence must be a positive integer, got: '" + seqStr + "'."));
                continue;
            }

            if (specCode == null || !validSpecCodes.contains(specCode)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-UNKNOWN-SPEC-CODE",
                    "Specialization Code '" + specCode + "' not found in Specializations file."));
                continue;
            }
            rows.add(new ValidatedReferenceBundle.ModuleRow(name, code, sequence, specCode));
        }
        return rows;
    }

    private List<ValidatedReferenceBundle.LabRow> validateLabs(
            String fileName, byte[] bytes, Set<String> validModuleCodes, List<GateError> errors) {
        List<ValidatedReferenceBundle.LabRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) {
            return rows;
        }

        Map<String, Integer> headers = readHeaders(sheet);
        List<GateError> colErrors = checkRequiredColumns(fileName, headers, "Title", "Module Code");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) {
                continue;
            }
            int rowNum = i + 1;
            String title = getCellString(row, headers.get("Title"));
            String moduleCode = getCellString(row, headers.get("Module Code"));

            if (title == null || title.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-LAB-TITLE",
                    "Lab title is blank."));
                continue;
            }
            if (moduleCode == null || !validModuleCodes.contains(moduleCode)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-UNKNOWN-MODULE-CODE",
                    "Module Code '" + moduleCode + "' not found in Modules file."));
                continue;
            }
            rows.add(new ValidatedReferenceBundle.LabRow(title, moduleCode));
        }
        return rows;
    }

    private List<ValidatedReferenceBundle.LearnerRow> validateLearners(
            String fileName, byte[] bytes, Set<String> validSpecCodes, List<GateError> errors) {
        List<ValidatedReferenceBundle.LearnerRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) {
            return rows;
        }

        Map<String, Integer> headers = readHeaders(sheet);
        List<GateError> colErrors = checkRequiredColumns(
            fileName, headers, "LearnerID", "Full Name", "Email", "Specialization Code");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        Set<String> seenIds = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) {
                continue;
            }
            int rowNum = i + 1;
            String learnerId = getCellString(row, headers.get("LearnerID"));
            String fullName = getCellString(row, headers.get("Full Name"));
            String email = getCellString(row, headers.get("Email"));
            String specCode = getCellString(row, headers.get("Specialization Code"));

            boolean rowHasError = false;

            if (learnerId == null || learnerId.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-LEARNER-ID",
                    "LearnerID is blank."));
                rowHasError = true;
            } else if (!seenIds.add(learnerId)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-DUP-LEARNER-ID",
                    "Duplicate LearnerID '" + learnerId + "'."));
                rowHasError = true;
            }

            if (email == null || email.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-LEARNER-EMAIL",
                    "Email is blank."));
                rowHasError = true;
            } else if (!seenEmails.add(email.toLowerCase())) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-DUP-LEARNER-EMAIL",
                    "Duplicate Email '" + email + "'."));
                rowHasError = true;
            }

            if (specCode == null || !validSpecCodes.contains(specCode)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-UNKNOWN-SPEC-CODE",
                    "Specialization Code '" + specCode + "' not found in Specializations file."));
                rowHasError = true;
            }

            if (!rowHasError) {
                rows.add(new ValidatedReferenceBundle.LearnerRow(learnerId, fullName, email, specCode));
            }
        }
        return rows;
    }

    private List<ValidatedReferenceBundle.InstructorContactRow> validateInstructors(
            String fileName, byte[] bytes, List<GateError> errors) {
        List<ValidatedReferenceBundle.InstructorContactRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) {
            return rows;
        }

        Map<String, Integer> headers = readHeaders(sheet);
        List<GateError> colErrors = checkRequiredColumns(
            fileName, headers, "InstructorID", "Full Name", "Email");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        Set<String> seenIds = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) {
                continue;
            }
            int rowNum = i + 1;
            String instructorId = getCellString(row, headers.get("InstructorID"));
            String fullName = getCellString(row, headers.get("Full Name"));
            String email = getCellString(row, headers.get("Email"));

            boolean rowHasError = false;

            if (instructorId == null || instructorId.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-INSTRUCTOR-ID",
                    "InstructorID is blank."));
                rowHasError = true;
            } else if (!seenIds.add(instructorId)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-DUP-INSTRUCTOR-ID",
                    "Duplicate InstructorID '" + instructorId + "'."));
                rowHasError = true;
            }

            if (email == null || email.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-INSTRUCTOR-EMAIL",
                    "Email is blank."));
                rowHasError = true;
            } else if (!seenEmails.add(email.toLowerCase())) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-DUP-INSTRUCTOR-EMAIL",
                    "Duplicate Email '" + email + "'."));
                rowHasError = true;
            }

            if (!rowHasError) {
                rows.add(new ValidatedReferenceBundle.InstructorContactRow(instructorId, fullName, email));
            }
        }
        return rows;
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

    private Map<String, Integer> readHeaders(Sheet sheet) {
        Map<String, Integer> headers = new HashMap<>();
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            return headers;
        }
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null) {
                String val = cell.getStringCellValue();
                if (val != null && !val.isBlank()) {
                    headers.put(val.trim(), c);
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
        if (row == null) {
            return true;
        }
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String val = getCellString(row, c);
                if (val != null && !val.isBlank()) {
                    return false;
                }
            }
        }
        return true;
    }

    private String getCellString(Row row, Integer colIndex) {
        if (row == null || colIndex == null) {
            return null;
        }
        Cell cell = row.getCell(colIndex);
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d)) {
                    yield String.valueOf((long) d);
                }
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
