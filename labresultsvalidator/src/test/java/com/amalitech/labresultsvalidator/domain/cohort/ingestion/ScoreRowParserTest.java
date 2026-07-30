package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

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
            new String[]{"2026-01-15", "Ama Owusu", "REST API Basics", "0.9", "Kofi Mensah"});

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
            assertThat(row.totalScore()).isEqualTo(new BigDecimal("0.9"));
            assertThat(row.reviewer()).isEqualTo("Kofi Mensah");
        }
    }

    @Test
    void parse_skipSheetName_isIgnoredEntirely() throws IOException {
        byte[] bytes = buildWorkbook("Template", true,
            new String[]{"2026-01-15", "Ama Owusu", "REST API Basics", "0.9", "Kofi Mensah"});

        try (var wb = open(bytes)) {
            ScoreRowParser.SheetParseResult result = parser.parse(FILE_NAME, wb);

            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).isEmpty();
        }
    }

    @Test
    void parse_missingRequiredColumn_reportsSheetErrorAndSkipsSheet() throws IOException {
        byte[] bytes = buildWorkbook("BEM01", false,
            new String[]{"2026-01-15", "Ama Owusu", "REST API Basics", "0.9"});

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
    void parse_blankRow_isSkipped() throws IOException {
        byte[] bytes = buildWorkbook("BEM01", true,
            new String[]{null, null, null, null, null},
            new String[]{"2026-01-15", "Ama Owusu", "REST API Basics", "0.9", "Kofi Mensah"});

        try (var wb = open(bytes)) {
            ScoreRowParser.SheetParseResult result = parser.parse(FILE_NAME, wb);

            assertThat(result.rows()).hasSize(1);
        }
    }
}
