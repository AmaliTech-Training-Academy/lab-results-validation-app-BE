package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.domain.standup.gate.ScoreSheetRowReader;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * B5 (sheet selection/structural check) + raw row extraction for the recurring grading-ingestion
 * pipeline. Reuses {@link ScoreSheetRowReader} — the same header-detection/cell-reading logic
 * {@code Gate4ScoreSheetValidator} uses against the same sheet layout. No {@code Status} filtering:
 * the sheet has no such column, every non-blank row is parsed (per the finalized change-detection
 * model, which drops the PRD's original B6 AC1 assumption).
 */
@Component
public class ScoreRowParser {

    private static final List<DateTimeFormatter> STRING_DATE_FORMATS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("M/d/yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH)
    );

    public record SheetParseResult(List<ParsedScoreRow> rows, List<RowError> errors) {
    }

    public SheetParseResult parse(String fileName, Workbook workbook) {
        List<ParsedScoreRow> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();

        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            String sheetName = sheet.getSheetName();

            if (ScoreSheetRowReader.SKIP_SHEETS.contains(sheetName.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }

            int headerRowIdx = ScoreSheetRowReader.findHeaderRowIndex(sheet);
            Map<String, Integer> headers = ScoreSheetRowReader.readHeadersFromRow(sheet.getRow(headerRowIdx));
            List<String> missing = ScoreSheetRowReader.findMissingColumns(headers);
            if (!missing.isEmpty()) {
                for (String col : missing) {
                    // Sheet-level: no row exists, so neither a reviewer nor a lab title does.
                    errors.add(new RowError(fileName, "sheet " + sheetName, "S2-MISSING-COLUMN",
                        "Required column '" + col + "' not found in sheet '" + sheetName + "'.",
                        null, null));
                }
                continue;
            }

            int reviewDateCol = headers.get("review date");
            int nspCol = headers.get("name of nsp");
            int labTitleCol = headers.get("lab title");
            int totalScoreCol = headers.get("total score");
            int reviewerCol = headers.get("reviewer");

            for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (ScoreSheetRowReader.isBlankRow(row)) {
                    continue;
                }

                String reviewDateRaw = ScoreSheetRowReader.getCellString(row, reviewDateCol);
                LocalDate reviewDate = parseReviewDate(row.getCell(reviewDateCol));
                String nspName = ScoreSheetRowReader.getCellString(row, nspCol);
                String labTitle = ScoreSheetRowReader.getCellString(row, labTitleCol);
                String totalScoreRaw = ScoreSheetRowReader.getCellString(row, totalScoreCol);
                BigDecimal totalScore = parseTotalScore(totalScoreRaw);
                String reviewer = ScoreSheetRowReader.getCellString(row, reviewerCol);

                rows.add(new ParsedScoreRow(fileName, sheetName, i + 1, reviewDateRaw, reviewDate,
                    nspName, labTitle, totalScoreRaw, totalScore, reviewer));
            }
        }

        return new SheetParseResult(rows, errors);
    }

    private LocalDate parseReviewDate(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            try {
                return DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalDate();
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        if (cell.getCellType() == CellType.STRING) {
            String text = cell.getStringCellValue().trim();
            for (DateTimeFormatter fmt : STRING_DATE_FORMATS) {
                try {
                    return LocalDate.parse(text, fmt);
                } catch (DateTimeParseException ignored) {
                    // try the next format
                }
            }
        }
        return null;
    }

    private BigDecimal parseTotalScore(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
