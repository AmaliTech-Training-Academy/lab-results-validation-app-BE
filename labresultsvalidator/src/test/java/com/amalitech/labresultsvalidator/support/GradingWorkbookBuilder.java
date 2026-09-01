package com.amalitech.labresultsvalidator.support;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds grading workbooks shaped like the ones instructors actually send.
 *
 * <p>Two details are copied from the real files in {@code OneDrive_2026-08-10/} rather than from
 * the specification, because the two disagree and the files are what the application will meet:
 *
 * <ul>
 *   <li><strong>The header is not on row 1.</strong> Real sheets carry an "Allocated Points" /
 *       "Allocated Weight" preamble above it, and the parser scans the first ten rows for the row
 *       with the most required columns. A fixture with the header on row 1 would never exercise
 *       that scan.</li>
 *   <li><strong>Total Score is not adjacent to the other columns</strong> and sits at a different
 *       index on nearly every real sheet, because per-criterion columns vary by specialization.
 *       Column lookup is by header name; hard-coding a tidy layout would hide a regression there.</li>
 * </ul>
 *
 * <p>The builder deliberately does <em>not</em> reproduce one property of the real files: theirs
 * leave Total Score empty and the grade lives in weighted criterion columns. That is a live
 * question for the PO, not something to bake into a fixture — so a total is written here, and the
 * real-file shape stays a case to run against the real files.
 */
public final class GradingWorkbookBuilder {

    /** Sheets the parser skips by name; included so a fixture proves the skip actually happens. */
    private static final List<String> METADATA_SHEETS = List.of("Template", "How To Use", "Ref");

    private final String sheetName;
    private final List<Object[]> rows = new ArrayList<>();

    private GradingWorkbookBuilder(String sheetName) {
        this.sheetName = sheetName;
    }

    public static GradingWorkbookBuilder sheet(String sheetName) {
        return new GradingWorkbookBuilder(sheetName);
    }

    /**
     * One graded row.
     *
     * @param totalScore the value for the Total Score cell; {@code null} writes a blank cell, which
     *                   is how "not yet graded" is expressed and must be skipped silently
     */
    public GradingWorkbookBuilder row(LocalDate reviewDate, String learnerName, String reviewer,
                                      String labTitle, Integer totalScore) {
        rows.add(new Object[]{reviewDate, learnerName, reviewer, labTitle, totalScore});
        return this;
    }

    /** A row whose Total Score cell holds text rather than a number — a real rejection path. */
    public GradingWorkbookBuilder rowWithRawScore(LocalDate reviewDate, String learnerName,
                                                  String reviewer, String labTitle, String rawScore) {
        rows.add(new Object[]{reviewDate, learnerName, reviewer, labTitle, rawScore});
        return this;
    }

    /** Writes the workbook and returns the path, creating parent directories as needed. */
    public Path writeTo(Path file) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            for (String metadata : METADATA_SHEETS) {
                workbook.createSheet(metadata).createRow(0).createCell(0)
                    .setCellValue("not a data sheet");
            }
            writeDataSheet(workbook.createSheet(sheetName));

            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not write grading workbook " + file, ex);
        }
    }

    private void writeDataSheet(Sheet sheet) {
        // The preamble a real template carries above the header row.
        Row points = sheet.createRow(0);
        points.createCell(4).setCellValue("Allocated Points");
        points.createCell(5).setCellValue(5);
        Row weights = sheet.createRow(1);
        weights.createCell(0).setCellValue("Passing Score");
        weights.createCell(1).setCellValue(0.8);

        // Header on row 3 (0-based 2), with Total Score deliberately far from the rest.
        Row header = sheet.createRow(2);
        header.createCell(0).setCellValue("Review Date");
        header.createCell(1).setCellValue("Name of NSP");
        header.createCell(2).setCellValue("Reviewer");
        header.createCell(3).setCellValue("Lab Title");
        header.createCell(4).setCellValue("Attempt");
        header.createCell(5).setCellValue("Code Quality");
        header.createCell(6).setCellValue("Documentation");
        header.createCell(9).setCellValue("Total Score");
        header.createCell(10).setCellValue("Remarks");

        int rowIndex = 3;
        for (Object[] values : rows) {
            Row row = sheet.createRow(rowIndex++);
            LocalDate reviewDate = (LocalDate) values[0];
            if (reviewDate != null) {
                // Written as text in an unambiguous ISO form; the parser accepts several formats
                // and a numeric date cell would additionally depend on cell styling.
                row.createCell(0).setCellValue(reviewDate.toString());
            }
            setIfPresent(row, 1, values[1]);
            setIfPresent(row, 2, values[2]);
            setIfPresent(row, 3, values[3]);
            row.createCell(4).setCellValue("1st");

            Object score = values[4];
            if (score instanceof Integer number) {
                row.createCell(9).setCellValue(number);
            } else if (score instanceof String text) {
                row.createCell(9).setCellValue(text);
            }
            // null leaves the cell absent — "not yet graded"
        }
    }

    private static void setIfPresent(Row row, int column, Object value) {
        if (value != null) {
            Cell cell = row.createCell(column);
            cell.setCellValue(value.toString());
        }
    }
}
