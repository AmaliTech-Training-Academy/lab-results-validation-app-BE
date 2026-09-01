package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreRowParserTest {

    private static final String FILE_NAME = "Instructor1.xlsx";
    private static final List<String> HEADERS =
        List.of("Review Date", "Name of NSP", "Lab Title", "Total Score", "Reviewer");

    private final ScoreRowParser parser = new ScoreRowParser();

    private byte[] buildWorkbook(String sheetName, boolean includeAllHeaders, String[]... dataRows) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);
            Row headerRow = sheet.createRow(0);
            List<String> headers = includeAllHeaders ? HEADERS : HEADERS.subList(0, HEADERS.size() - 1);
            for (int c = 0; c < headers.size(); c++) {
                headerRow.createCell(c).setCellValue(headers.get(c));
            }
            int rowIdx = 1;
            for (String[] data : dataRows) {
                Row row = sheet.createRow(rowIdx++);
                for (int c = 0; c < data.length; c++) {
                    if (data[c] != null) {
                        row.createCell(c).setCellValue(data[c]);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private org.apache.poi.ss.usermodel.Workbook open(byte[] bytes) {
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void parse_validRow_extractsAllFields() throws IOException {
        byte[] bytes = buildWorkbook("BEM01", true,
            new String[]{"2026-01-15", "Ama Owusu", "REST API Basics", "90", "Kofi Mensah"});

        try (var wb = open(bytes)) {
            ScoreRowParser.SheetParseResult result = parser.parse(FILE_NAME, wb);

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows()).hasSize(1);
            ParsedScoreRow row = result.rows().get(0);
            assertThat(row.fileName()).isEqualTo(FILE_NAME);
            assertThat(row.sheetName()).isEqualTo("BEM01");
            assertThat(row.reviewDate()).isEqualTo(LocalDate.of(2026, 1, 15));
            assertThat(row.nspName()).isEqualTo("Ama Owusu");
            assertThat(row.labTitle()).isEqualTo("REST API Basics");
            assertThat(row.totalScore()).isEqualTo(new BigDecimal("90"));
            assertThat(row.reviewer()).isEqualTo("Kofi Mensah");
        }
    }

    @Test
    void parse_skipSheetName_isIgnoredEntirely() throws IOException {
        byte[] bytes = buildWorkbook("Template", true,
            new String[]{"2026-01-15", "Ama Owusu", "REST API Basics", "90", "Kofi Mensah"});

        try (var wb = open(bytes)) {
            ScoreRowParser.SheetParseResult result = parser.parse(FILE_NAME, wb);

            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).isEmpty();
        }
    }

    @Test
    void parse_missingRequiredColumn_reportsSheetErrorAndSkipsSheet() throws IOException {
        byte[] bytes = buildWorkbook("BEM01", false,
            new String[]{"2026-01-15", "Ama Owusu", "REST API Basics", "90"});

        try (var wb = open(bytes)) {
            ScoreRowParser.SheetParseResult result = parser.parse(FILE_NAME, wb);

            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).anyMatch(e -> "S2-MISSING-COLUMN".equals(e.rule())
                && e.message().contains("reviewer"));
        }
    }

    @Test
    void parse_unparseableDateAndScore_keepsRawTextButLeavesParsedValueNull() throws IOException {
        byte[] bytes = buildWorkbook("BEM01", true,
            new String[]{"not-a-date", "Ama Owusu", "REST API Basics", "not-a-number", "Kofi Mensah"});

        try (var wb = open(bytes)) {
            ScoreRowParser.SheetParseResult result = parser.parse(FILE_NAME, wb);

            assertThat(result.rows()).hasSize(1);
            ParsedScoreRow row = result.rows().get(0);
            assertThat(row.reviewDateRaw()).isEqualTo("not-a-date");
            assertThat(row.reviewDate()).isNull();
            assertThat(row.totalScoreRaw()).isEqualTo("not-a-number");
            assertThat(row.totalScore()).isNull();
        }
    }

    @Test
    void parse_percentFormattedScoreCell_convertsRawFractionToWholeNumber() throws IOException {
        // Excel's percent-format trap: typing "62" into a cell formatted as a percentage leaves
        // the cell storing the raw fraction 0.62 — the ×100 is only applied at display time. Reading
        // the raw numeric value without checking the cell's format silently loses that factor of
        // 100. The Total Score column must detect this from the cell's actual format and correct
        // for it, rather than passing 0.62 downstream as if it were the real score.
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Module-5");
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < HEADERS.size(); c++) {
                headerRow.createCell(c).setCellValue(HEADERS.get(c));
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("2026-01-15");
            row.createCell(1).setCellValue("Ama Owusu");
            row.createCell(2).setCellValue("REST API Basics");
            CellStyle percentStyle = wb.createCellStyle();
            percentStyle.setDataFormat(wb.createDataFormat().getFormat("0%"));
            Cell scoreCell = row.createCell(3);
            scoreCell.setCellValue(0.62);
            scoreCell.setCellStyle(percentStyle);
            row.createCell(4).setCellValue("Kofi Mensah");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);

            try (var readBack = open(out.toByteArray())) {
                ScoreRowParser.SheetParseResult result = parser.parse(FILE_NAME, readBack);

                assertThat(result.rows()).hasSize(1);
                ParsedScoreRow parsedRow = result.rows().get(0);
                assertThat(parsedRow.totalScoreRaw()).isEqualTo("62");
                assertThat(parsedRow.totalScore()).isEqualTo(new BigDecimal("62"));
            }
        }
    }

    @Test
    void parse_blankRow_isSkipped() throws IOException {
        byte[] bytes = buildWorkbook("BEM01", true,
            new String[]{null, null, null, null, null},
            new String[]{"2026-01-15", "Ama Owusu", "REST API Basics", "90", "Kofi Mensah"});

        try (var wb = open(bytes)) {
            ScoreRowParser.SheetParseResult result = parser.parse(FILE_NAME, wb);

            assertThat(result.rows()).hasSize(1);
        }
    }
}
