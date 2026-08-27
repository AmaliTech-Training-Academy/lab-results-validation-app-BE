package com.amalitech.labresultsvalidator.domain.standup.gate;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure helpers for reading a grading score sheet's header row and data rows with Apache POI.
 * Shared by {@link Gate4ScoreSheetValidator} (stand-up validation of the empty template) and the
 * recurring grading-ingestion pipeline (domain.cohort.ingestion) that parses graded rows — both
 * need the same header-detection/cell-reading behavior against the same sheet layout.
 */
public final class ScoreSheetRowReader {

    // Matched case-insensitively after trimming — expand as new template variants appear.
    public static final Set<String> SKIP_SHEETS = Set.of(
        "template", "how-to", "ref",
        "how to use", "rating scale ref", "sheet1"
    );

    // All header names stored/compared lowercase.
    public static final List<String> REQUIRED_COLUMNS =
        List.of("review date", "name of nsp", "lab title", "total score", "reviewer");

    private ScoreSheetRowReader() {
    }

    // Scans the first 10 rows and returns the index of the one with the most required-column matches.
    public static int findHeaderRowIndex(Sheet sheet) {
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
    public static Map<String, Integer> readHeadersFromRow(Row row) {
        Map<String, Integer> headers = new HashMap<>();
        if (row == null) {
            return headers;
        }
        for (int c = 0; c < row.getLastCellNum(); c++) {
            String val = getCellString(row, c);
            if (val != null && !val.isBlank()) {
                headers.put(val.trim().toLowerCase(java.util.Locale.ROOT), c);
            }
        }
        return headers;
    }

    public static List<String> findMissingColumns(Map<String, Integer> headers) {
        List<String> missing = new ArrayList<>();
        for (String col : REQUIRED_COLUMNS) {
            if (!headers.containsKey(col)) {
                missing.add(col);
            }
        }
        return missing;
    }

    public static boolean isBlankRow(Row row) {
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

    // Excel stores a percent-formatted cell's raw fraction, not its displayed value: typing "62"
    // into a cell formatted as a percentage leaves the cell holding 0.62, which
    // getNumericCellValue() returns as-is — the ×100 is purely a display-time multiplier applied by
    // the percent format, invisible to any code that reads the underlying number. Score columns are
    // the only place this bites (dates/names/lab titles are never percent-formatted), so this is a
    // dedicated read for the Total Score column rather than folded into getCellString, which every
    // other column also shares.
    public static String getScoreCellString(Row row, Integer colIndex) {
        if (row == null || colIndex == null) {
            return null;
        }
        Cell cell = row.getCell(colIndex);
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC && isPercentFormatted(cell)) {
            BigDecimal asWhole = BigDecimal.valueOf(cell.getNumericCellValue()).multiply(BigDecimal.valueOf(100));
            return asWhole.stripTrailingZeros().toPlainString();
        }
        return getCellString(row, colIndex);
    }

    private static boolean isPercentFormatted(Cell cell) {
        String format = cell.getCellStyle().getDataFormatString();
        return format != null && format.contains("%");
    }

    public static String getCellString(Row row, Integer colIndex) {
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
