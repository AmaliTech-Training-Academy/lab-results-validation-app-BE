package com.amalitech.labresultsvalidator.domain.standup.gate;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreSheetRowReaderTest {

    @Test
    void findHeaderRowIndex_skipsTitleRowsAboveTheRealHeader() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("BEM01");
            sheet.createRow(0).createCell(0).setCellValue("Cohort 2026 — Score Sheet");
            Row headerRow = sheet.createRow(1);
            List<String> headers = List.of("Review Date", "Name of NSP", "Lab Title", "Total Score", "Reviewer");
            for (int c = 0; c < headers.size(); c++) {
                headerRow.createCell(c).setCellValue(headers.get(c));
            }

            assertThat(ScoreSheetRowReader.findHeaderRowIndex(sheet)).isEqualTo(1);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void readHeadersFromRow_isCaseInsensitiveAndTrimmed() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("BEM01");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("  Review Date  ");
            row.createCell(1).setCellValue("NAME OF NSP");

            Map<String, Integer> headers = ScoreSheetRowReader.readHeadersFromRow(row);

            assertThat(headers).containsEntry("review date", 0).containsEntry("name of nsp", 1);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void findMissingColumns_reportsEveryRequiredColumnNotPresent() {
        Map<String, Integer> headers = Map.of("review date", 0, "name of nsp", 1);

        List<String> missing = ScoreSheetRowReader.findMissingColumns(headers);

        assertThat(missing).containsExactlyInAnyOrder("lab title", "total score", "reviewer");
    }

    @Test
    void findMissingColumns_emptyWhenAllPresent() {
        Map<String, Integer> headers = Map.of(
            "review date", 0, "name of nsp", 1, "lab title", 2, "total score", 3, "reviewer", 4);

        assertThat(ScoreSheetRowReader.findMissingColumns(headers)).isEmpty();
    }

    @Test
    void isBlankRow_trueForNullAndAllBlankCells() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("BEM01");
            Row blank = sheet.createRow(0);
            blank.createCell(0);

            assertThat(ScoreSheetRowReader.isBlankRow(null)).isTrue();
            assertThat(ScoreSheetRowReader.isBlankRow(blank)).isTrue();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void isBlankRow_falseWhenAnyCellHasText() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("BEM01");
            Row row = sheet.createRow(0);
            row.createCell(2).setCellValue("Ama Owusu");

            assertThat(ScoreSheetRowReader.isBlankRow(row)).isFalse();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void getCellString_convertsWholeNumberNumericWithoutDecimal() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("BEM01");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(90);

            assertThat(ScoreSheetRowReader.getCellString(row, 0)).isEqualTo("90");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void getCellString_nullForMissingCellOrColumn() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("BEM01");
            Row row = sheet.createRow(0);

            assertThat(ScoreSheetRowReader.getCellString(row, 5)).isNull();
            assertThat(ScoreSheetRowReader.getCellString(null, 0)).isNull();
            assertThat(ScoreSheetRowReader.getCellString(row, null)).isNull();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void getScoreCellString_percentFormattedCell_convertsRawFractionToWholeNumber() {
        // A cell formatted as a percentage stores the raw fraction (typing "62" into a percent-
        // formatted cell leaves it holding 0.62) — the ×100 is a display-time-only multiplier.
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("BEM01");
            Row row = sheet.createRow(0);
            CellStyle percentStyle = wb.createCellStyle();
            percentStyle.setDataFormat(wb.createDataFormat().getFormat("0%"));
            Cell cell = row.createCell(0);
            cell.setCellValue(0.62);
            cell.setCellStyle(percentStyle);

            assertThat(ScoreSheetRowReader.getScoreCellString(row, 0)).isEqualTo("62");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void getScoreCellString_plainNumericCell_behavesLikeGetCellString() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("BEM01");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(90);

            assertThat(ScoreSheetRowReader.getScoreCellString(row, 0)).isEqualTo("90");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
