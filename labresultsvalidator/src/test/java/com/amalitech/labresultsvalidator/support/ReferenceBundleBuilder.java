package com.amalitech.labresultsvalidator.support;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the five reference workbooks Gate 3 validates, cross-referenced so the bundle is
 * internally consistent by construction.
 *
 * <p>The shapes are taken from {@code Gate3ReferenceValidator}, which matches columns
 * case-insensitively by header name and scans the first ten rows for the header:
 *
 * <pre>
 *   Specializations.xlsx      SpecializationID | Specialization
 *   Module Setup.xlsx         SpecializationID | ModuleID | Module Name
 *   Lab Reference.xlsx        ModuleID | AssessmentID | Lab Title
 *   Trainee Database.xlsx     Amalitech Email | Full Name | Specialization
 *   Instructor Database.xlsx  Name | Email | Specialization
 * </pre>
 *
 * <p>Note that learners and instructors reference a specialization by its <em>name</em>, resolved
 * against the Specializations file, while modules and labs use ids. Getting that backwards produces
 * a {@code G3-UNKNOWN-SPEC-NAME} that looks like a bug in the validator and is not.
 *
 * <p>Individual files can be omitted or renamed on purpose — {@link #withoutFile(String)} and
 * {@link #renamingFile(String, String)} — because Gate 3's failure paths are the half that has
 * never been executed: a missing or misnamed reference file, and the guarantee that a failure
 * commits nothing.
 */
public final class ReferenceBundleBuilder {

    public static final String SPEC_ID = "SPEC-BE";
    public static final String SPEC_NAME = "Backend Engineering";
    public static final String MODULE_ID = "MOD-1";
    public static final String MODULE_NAME = "Module 1";
    public static final String LAB_TITLE = "Provisioning a Virtual Network";
    public static final String SECOND_LAB_TITLE = "Recipe Browser App";

    private final List<String[]> learners = new ArrayList<>();
    private final List<String[]> instructors = new ArrayList<>();
    private String omittedFile;
    private String renameFrom;
    private String renameTo;

    public static ReferenceBundleBuilder bundle() {
        return new ReferenceBundleBuilder();
    }

    public ReferenceBundleBuilder learner(String fullName, String email) {
        learners.add(new String[]{email, fullName, SPEC_NAME});
        return this;
    }

    public ReferenceBundleBuilder instructor(String fullName, String email) {
        instructors.add(new String[]{fullName, email, SPEC_NAME});
        return this;
    }

    /** Leaves one reference file out entirely, to drive Gate 3's missing-file path. */
    public ReferenceBundleBuilder withoutFile(String fileName) {
        this.omittedFile = fileName;
        return this;
    }

    /** Writes one reference file under the wrong name, to drive Gate 3's misnamed-file path. */
    public ReferenceBundleBuilder renamingFile(String fileName, String wrongName) {
        this.renameFrom = fileName;
        this.renameTo = wrongName;
        return this;
    }

    /**
     * Writes the bundle into {@code <cohortFolder>/Reference Data/} and creates an empty
     * {@code Lab Scores/} folder alongside it, which is the layout Gate 2 requires.
     */
    public void writeTo(Path cohortFolder) {
        Path referenceData = cohortFolder.resolve("Reference Data");
        try {
            Files.createDirectories(referenceData);
            Files.createDirectories(cohortFolder.resolve("Lab Scores"));
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not create the cohort folder layout", ex);
        }

        write(referenceData, "Specializations.xlsx", "Specializations",
            new String[]{"SpecializationID", "Specialization"},
            List.<String[]>of(new String[]{SPEC_ID, SPEC_NAME}));

        write(referenceData, "Module Setup.xlsx", "Modules",
            new String[]{"SpecializationID", "ModuleID", "Module Name"},
            List.<String[]>of(new String[]{SPEC_ID, MODULE_ID, MODULE_NAME}));

        write(referenceData, "Lab Reference.xlsx", "Labs",
            new String[]{"ModuleID", "AssessmentID", "Lab Title"},
            List.of(new String[]{MODULE_ID, "ASMT-1", LAB_TITLE},
                    new String[]{MODULE_ID, "ASMT-2", SECOND_LAB_TITLE}));

        write(referenceData, "Trainee Database.xlsx", "Trainees",
            new String[]{"Amalitech Email", "Full Name", "Specialization"}, learners);

        write(referenceData, "Instructor Database.xlsx", "Instructors",
            new String[]{"Name", "Email", "Specialization"}, instructors);
    }

    private void write(Path folder, String fileName, String sheetName,
                       String[] header, List<String[]> rows) {
        if (fileName.equals(omittedFile)) {
            return;
        }
        String target = fileName.equals(renameFrom) ? renameTo : fileName;

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < header.length; c++) {
                headerRow.createCell(c).setCellValue(header[c]);
            }
            int r = 1;
            for (String[] values : rows) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < values.length; c++) {
                    row.createCell(c).setCellValue(values[c]);
                }
            }
            try (OutputStream out = Files.newOutputStream(folder.resolve(target))) {
                workbook.write(out);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not write reference file " + target, ex);
        }
    }
}
