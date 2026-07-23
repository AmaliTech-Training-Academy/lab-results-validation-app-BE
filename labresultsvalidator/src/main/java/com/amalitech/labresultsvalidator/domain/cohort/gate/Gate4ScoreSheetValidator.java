package com.amalitech.labresultsvalidator.domain.cohort.gate;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Lab;
import com.amalitech.labresultsvalidator.domain.cohort.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Learner;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.SpecializationRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class Gate4ScoreSheetValidator {

    private static final Logger LOG = LoggerFactory.getLogger(Gate4ScoreSheetValidator.class);
    private static final long MAX_ENTRY_SIZE = 20L * 1024 * 1024;
    private static final Set<String> SKIP_SHEETS = Set.of("Template", "How-To", "Ref");
    private static final List<String> REQUIRED_COLUMNS =
        List.of("LearnerID", "InstructorID", "Lab Title", "Total Score", "Status", "Date Added");

    private final GraphDriveService graphDriveService;
    private final SharePointProperties sharePointProperties;
    private final LabModuleRepository labModuleRepository;
    private final LearnerRepository learnerRepository;
    private final LabRepository labRepository;
    private final SpecializationRepository specializationRepository;

    public Gate4ScoreSheetValidator(
        GraphDriveService graphDriveService,
        SharePointProperties sharePointProperties,
        LabModuleRepository labModuleRepository,
        LearnerRepository learnerRepository,
        LabRepository labRepository,
        SpecializationRepository specializationRepository
    ) {
        this.graphDriveService = graphDriveService;
        this.sharePointProperties = sharePointProperties;
        this.labModuleRepository = labModuleRepository;
        this.learnerRepository = learnerRepository;
        this.labRepository = labRepository;
        this.specializationRepository = specializationRepository;
    }

    public Gate4Result validate(String driveId, String scoresFolderItemId, UUID cohortId) {
        List<DriveItemInfo> scoreFiles;
        try {
            scoreFiles = graphDriveService.listChildren(driveId, scoresFolderItemId);
        } catch (GraphAccessException ex) {
            return new Gate4Result(GateResult.fail(null, null, "G4-ACCESS",
                "Cannot list scores folder contents."));
        }

        List<DriveItemInfo> xlsxFiles = scoreFiles.stream()
            .filter(f -> !f.isFolder() && f.name() != null && f.name().endsWith(".xlsx"))
            .collect(Collectors.toList());

        List<Specialization> specs = specializationRepository.findAllByCohortId(cohortId);
        Set<UUID> specIds = specs.stream().map(Specialization::getId).collect(Collectors.toSet());
        List<LabModule> modules = labModuleRepository.findAllBySpecializationIdIn(specIds);

        Map<String, LabModule> modulesByCode = modules.stream()
            .collect(Collectors.toMap(LabModule::getCode, m -> m, (a, b) -> a));

        List<GateError> allErrors = new ArrayList<>();

        for (DriveItemInfo file : xlsxFiles) {
            byte[] bytes;
            try {
                bytes = graphDriveService.downloadFile(driveId, file.itemId());
            } catch (GraphAccessException ex) {
                allErrors.add(new GateError(file.name(), null, "G4-DOWNLOAD-FAIL",
                    "Could not download score file '" + file.name() + "': " + ex.getMessage()));
                continue;
            }

            processScoreFile(file.name(), bytes, modulesByCode, cohortId, allErrors);
        }

        if (!allErrors.isEmpty()) {
            return new Gate4Result(GateResult.fail(allErrors));
        }
        return new Gate4Result(GateResult.pass());
    }

    private void processScoreFile(
        String fileName,
        byte[] bytes,
        Map<String, LabModule> modulesByCode,
        UUID cohortId,
        List<GateError> errors
    ) {
        ZipSecureFile.setMinInflateRatio(0);
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_SIZE);

        Workbook wb;
        try {
            wb = WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            LOG.warn("Failed to parse score workbook {}: {}", fileName, ex.getMessage());
            errors.add(new GateError(fileName, null, "G4-PARSE-FAIL",
                "Could not parse score workbook '" + fileName + "': " + ex.getMessage()));
            return;
        } catch (Exception ex) {
            LOG.warn("Unexpected error parsing score workbook {}: {}", fileName, ex.getMessage());
            errors.add(new GateError(fileName, null, "G4-PARSE-FAIL",
                "Unexpected error reading '" + fileName + "': " + ex.getMessage()));
            return;
        }

        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            Sheet sheet = wb.getSheetAt(s);
            String sheetName = sheet.getSheetName();

            if (SKIP_SHEETS.contains(sheetName)) {
                continue;
            }

            LabModule module = modulesByCode.get(sheetName);
            if (module == null) {
                errors.add(new GateError(fileName, "sheet " + sheetName, "G4-UNKNOWN-SHEET",
                    "Sheet '" + sheetName + "' does not match any known module code for this cohort."));
                continue;
            }

            Map<String, Integer> headers = readHeaders(sheet);
            List<GateError> colErrors = checkRequiredColumns(fileName, sheetName, headers);
            if (!colErrors.isEmpty()) {
                errors.addAll(colErrors);
                continue;
            }

            List<Lab> moduleLabs = labRepository.findAllByModuleIdIn(List.of(module.getId()));
            Map<String, Lab> labsByTitleLower = moduleLabs.stream()
                .collect(Collectors.toMap(l -> l.getTitle().toLowerCase(), l -> l, (a, b) -> a));

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row)) {
                    continue;
                }
                int rowNum = i + 1;
                String learnerId = getCellString(row, headers.get("LearnerID"));
                String labTitle = getCellString(row, headers.get("Lab Title"));

                if (learnerId == null || learnerId.isBlank()) {
                    errors.add(new GateError(fileName, "row " + rowNum, "G4-BLANK-LEARNER-ID",
                        "LearnerID is blank in sheet '" + sheetName + "'."));
                    continue;
                }

                Optional<Learner> learnerOpt = learnerRepository.findByLearnerIdAndCohortId(learnerId, cohortId);
                if (learnerOpt.isEmpty()) {
                    errors.add(new GateError(fileName, "row " + rowNum, "G4-UNKNOWN-LEARNER",
                        "LearnerID '" + learnerId + "' not found for this cohort."));
                    continue;
                }

                Learner learner = learnerOpt.get();
                if (!learner.getSpecializationId().equals(module.getSpecializationId())) {
                    errors.add(new GateError(fileName, "row " + rowNum, "G4-WRONG-SPECIALIZATION",
                        "Learner '" + learnerId + "' belongs to a different specialization than module '"
                            + sheetName + "'."));
                }

                if (labTitle == null || labTitle.isBlank()) {
                    errors.add(new GateError(fileName, "row " + rowNum, "G4-BLANK-LAB-TITLE",
                        "Lab Title is blank in sheet '" + sheetName + "'."));
                    continue;
                }

                if (!labsByTitleLower.containsKey(labTitle.toLowerCase())) {
                    errors.add(new GateError(fileName, "row " + rowNum, "G4-UNKNOWN-LAB",
                        "Lab Title '" + labTitle + "' not found in module '" + sheetName + "'."));
                }
            }
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
            String fileName, String sheetName, Map<String, Integer> headers) {
        List<GateError> errors = new ArrayList<>();
        for (String col : REQUIRED_COLUMNS) {
            if (!headers.containsKey(col)) {
                errors.add(new GateError(fileName, "sheet " + sheetName, "G4-MISSING-COLUMN",
                    "Required column '" + col + "' not found in sheet '" + sheetName + "'."));
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
