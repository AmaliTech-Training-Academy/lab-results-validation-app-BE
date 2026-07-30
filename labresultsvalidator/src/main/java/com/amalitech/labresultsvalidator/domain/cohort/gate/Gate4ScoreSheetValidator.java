package com.amalitech.labresultsvalidator.domain.cohort.gate;

import com.amalitech.labresultsvalidator.domain.cohort.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.cohort.service.Gate4EventService;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class Gate4ScoreSheetValidator {

    private static final Logger LOG = LoggerFactory.getLogger(Gate4ScoreSheetValidator.class);

    // Matched case-insensitively after trimming — expand as new template variants appear.
    private static final Set<String> SKIP_SHEETS = Set.of(
        "template", "how-to", "ref",
        "how to use", "rating scale ref", "sheet1"
    );

    // All header names stored/compared lowercase.
    private static final List<String> REQUIRED_COLUMNS =
        List.of("review date", "name of nsp", "lab title", "total score", "reviewer");

    private final GraphDriveService graphDriveService;
    private final LearnerRepository learnerRepository;

    public Gate4ScoreSheetValidator(
        GraphDriveService graphDriveService,
        LearnerRepository learnerRepository
    ) {
        this.graphDriveService = graphDriveService;
        this.learnerRepository = learnerRepository;
    }

    public Gate4Result validate(String driveId, String scoresFolderItemId, UUID cohortId,
                                UUID jobId, Gate4EventService eventService) {
        List<DriveItemInfo> scoreFolderChildren;
        try {
            scoreFolderChildren = graphDriveService.listChildren(driveId, scoresFolderItemId);
        } catch (GraphAccessException ex) {
            return new Gate4Result(GateResult.fail(null, null, "G4-ACCESS",
                "Cannot list scores folder contents."));
        }

        LOG.info("[gate4] scores folder contains {} item(s): {}", scoreFolderChildren.size(),
            scoreFolderChildren.stream()
                .map(i -> (i.isFolder() ? "[DIR] " : "[FILE] ") + i.name())
                .collect(Collectors.toList()));

        // Lab Scores may contain scenario subfolders or score sheets directly (production layout).
        List<DriveItemInfo> xlsxFiles = new ArrayList<>();
        List<GateError> accessErrors = new ArrayList<>();
        for (DriveItemInfo item : scoreFolderChildren) {
            if (item.isFolder()) {
                LOG.info("[gate4] enumerating scenario folder '{}'", item.name());
                try {
                    List<DriveItemInfo> scenarioChildren =
                        graphDriveService.listChildren(driveId, item.itemId());
                    for (DriveItemInfo child : scenarioChildren) {
                        if (!child.isFolder() && child.name() != null
                                && child.name().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                            xlsxFiles.add(child);
                        }
                    }
                } catch (GraphAccessException ex) {
                    accessErrors.add(new GateError(item.name(), null, "G4-ACCESS",
                        "Cannot list scenario subfolder '" + item.name() + "': " + ex.getMessage()));
                }
            } else if (item.name() != null
                    && item.name().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                LOG.debug("[gate4] found score sheet directly in scores folder: '{}'", item.name());
                xlsxFiles.add(item);
            }
        }
        if (!accessErrors.isEmpty()) {
            return new Gate4Result(GateResult.fail(accessErrors));
        }

        LOG.info("[gate4] found {} score sheet(s): {}", xlsxFiles.size(),
            xlsxFiles.stream().map(DriveItemInfo::name).collect(Collectors.toList()));

        // Load all learner full names for this cohort once — used for NSP name lookup across all files.
        Set<String> learnerNames = learnerRepository.findAllByCohortId(cohortId).stream()
            .map(l -> l.getFullName().trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());

        List<GateError> allErrors = new ArrayList<>();

        for (DriveItemInfo file : xlsxFiles) {
            eventService.emit(jobId, "file.start", Map.of("file", file.name()));

            byte[] bytes;
            try {
                bytes = graphDriveService.downloadFile(driveId, file.itemId());
            } catch (GraphAccessException ex) {
                GateError err = new GateError(file.name(), null, "G4-DOWNLOAD-FAIL",
                    "Could not download score file '" + file.name() + "': " + ex.getMessage());
                allErrors.add(err);
                eventService.emit(jobId, "file.failed", Map.of(
                    "file", file.name(),
                    "errors", List.of(err.message())
                ));
                continue;
            }

            List<GateError> fileErrors = processScoreFile(file.name(), bytes, learnerNames);
            if (fileErrors.isEmpty()) {
                eventService.emit(jobId, "file.passed", Map.of("file", file.name()));
            } else {
                allErrors.addAll(fileErrors);
                eventService.emit(jobId, "file.failed", Map.of(
                    "file", file.name(),
                    "errors", fileErrors.stream()
                        .map(e -> "[" + e.file() + " | " + e.location() + "] " + e.message())
                        .collect(Collectors.toList())
                ));
            }
        }

        if (!allErrors.isEmpty()) {
            return new Gate4Result(GateResult.fail(allErrors));
        }
        return new Gate4Result(GateResult.pass());
    }

    private List<GateError> processScoreFile(
        String fileName,
        byte[] bytes,
        Set<String> learnerNames
    ) {
        List<GateError> errors = new ArrayList<>();

        // ZipSecureFile limits are JVM-global and are now applied once at startup by
        // PoiHardeningConfig. Setting them here per file meant this loop silently decided the
        // zip-bomb policy for every other POI caller in the process — including a
        // setMinInflateRatio(0) that disabled the guard outright (risk R-10).
        Workbook wb;
        try {
            wb = WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            LOG.warn("Failed to parse score workbook {}: {}", fileName, ex.getMessage());
            errors.add(new GateError(fileName, null, "G4-PARSE-FAIL",
                "Could not parse score workbook '" + fileName + "': " + ex.getMessage()));
            return errors;
        } catch (Exception ex) {
            LOG.warn("Unexpected error parsing score workbook {}: {}", fileName, ex.getMessage());
            errors.add(new GateError(fileName, null, "G4-PARSE-FAIL",
                "Unexpected error reading '" + fileName + "': " + ex.getMessage()));
            return errors;
        }

        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            Sheet sheet = wb.getSheetAt(s);
            String sheetName = sheet.getSheetName();

            if (SKIP_SHEETS.contains(sheetName.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }

            int headerRowIdx = findHeaderRowIndex(sheet);
            Map<String, Integer> headers = readHeadersFromRow(sheet.getRow(headerRowIdx));
            List<GateError> colErrors = checkRequiredColumns(fileName, sheetName, headers);
            if (!colErrors.isEmpty()) {
                errors.addAll(colErrors);
                continue;
            }

            int nspCol = headers.get("name of nsp");
            int scoreCol = headers.get("total score");

            for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row)) {
                    continue;
                }
                int rowNum = i + 1;

                String nspName = getCellString(row, nspCol);
                String totalScore = getCellString(row, scoreCol);

                if (nspName == null || nspName.isBlank()) {
                    errors.add(new GateError(fileName, "sheet " + sheetName + " row " + rowNum,
                        "G4-BLANK-NSP", "Name of NSP is blank."));
                } else if (!learnerNames.contains(nspName.trim().toLowerCase(Locale.ROOT))) {
                    errors.add(new GateError(fileName, "sheet " + sheetName + " row " + rowNum,
                        "G4-UNKNOWN-NSP",
                        "NSP '" + nspName + "' does not match any learner in this cohort."));
                }

                if (totalScore == null || totalScore.isBlank()) {
                    errors.add(new GateError(fileName, "sheet " + sheetName + " row " + rowNum,
                        "G4-BLANK-SCORE", "Total Score is blank."));
                }
            }
        }
        return errors;
    }

    // Scans the first 10 rows and returns the index of the one with the most required-column matches.
    private int findHeaderRowIndex(Sheet sheet) {
        int best = 0;
        long bestMatches = 0;
        int limit = Math.min(10, sheet.getLastRowNum() + 1);
        for (int r = 0; r < limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, Integer> candidate = readHeadersFromRow(row);
            long matches = REQUIRED_COLUMNS.stream().filter(candidate::containsKey).count();
            if (matches > bestMatches) {
                bestMatches = matches;
                best = r;
            }
        }
        return best;
    }

    // Headers stored lowercase so all column lookups are case-insensitive.
    private Map<String, Integer> readHeadersFromRow(Row row) {
        Map<String, Integer> headers = new HashMap<>();
        if (row == null) {
            return headers;
        }
        for (int c = 0; c < row.getLastCellNum(); c++) {
            String val = getCellString(row, c);
            if (val != null && !val.isBlank()) {
                headers.put(val.trim().toLowerCase(Locale.ROOT), c);
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
                    "Required column '" + col + "' not found in sheet '"
                        + sheetName + "' in file '" + fileName + "'."));
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
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCachedFormulaResultType() == CellType.STRING
                ? cell.getStringCellValue().trim()
                : String.valueOf(cell.getNumericCellValue());
            default -> null;
        };
    }
}
