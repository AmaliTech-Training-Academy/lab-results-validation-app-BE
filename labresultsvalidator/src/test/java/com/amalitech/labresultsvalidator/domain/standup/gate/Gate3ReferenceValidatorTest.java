package com.amalitech.labresultsvalidator.domain.standup.gate;

import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Gate3ReferenceValidatorTest {

    private static final String DRIVE_ID = "drive-1";
    private static final String REF_FOLDER_ID = "ref-folder-1";
    private static final String SPECS_FILE = "Specializations.xlsx";
    private static final String MODULES_FILE = "Modules.xlsx";
    private static final String LABS_FILE = "Labs.xlsx";
    private static final String LEARNERS_FILE = "Learners.xlsx";
    private static final String INSTRUCTORS_FILE = "InstructorContacts.xlsx";

    @Mock
    private GraphDriveService graphDriveService;

    private Gate3ReferenceValidator validator;

    @BeforeEach
    void setUp() {
        SharePointProperties properties = new SharePointProperties(
            "reference-data",
            "scores",
            new SharePointProperties.RefFiles(SPECS_FILE, MODULES_FILE, LABS_FILE, LEARNERS_FILE, INSTRUCTORS_FILE),
            20L * 1024 * 1024,
            4
        );
        validator = new Gate3ReferenceValidator(graphDriveService, properties);
    }

    private void stubFolderContents(byte[] specsBytes, byte[] modulesBytes, byte[] labsBytes, byte[] learnersBytes) {
        List<DriveItemInfo> children = List.of(
            new DriveItemInfo(DRIVE_ID, "id-specs", SPECS_FILE, false, "site-1"),
            new DriveItemInfo(DRIVE_ID, "id-modules", MODULES_FILE, false, "site-1"),
            new DriveItemInfo(DRIVE_ID, "id-labs", LABS_FILE, false, "site-1"),
            new DriveItemInfo(DRIVE_ID, "id-learners", LEARNERS_FILE, false, "site-1")
        );
        when(graphDriveService.listChildren(DRIVE_ID, REF_FOLDER_ID)).thenReturn(children);
        when(graphDriveService.downloadFile(DRIVE_ID, "id-specs")).thenReturn(specsBytes);
        when(graphDriveService.downloadFile(DRIVE_ID, "id-modules")).thenReturn(modulesBytes);
        when(graphDriveService.downloadFile(DRIVE_ID, "id-labs")).thenReturn(labsBytes);
        when(graphDriveService.downloadFile(DRIVE_ID, "id-learners")).thenReturn(learnersBytes);
    }

    // Writes `titleRows` blank/title rows before the header row, then the header, then the data rows.
    private byte[] buildWorkbook(int titleRows, List<String> headers, List<List<String>> dataRows) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            int rowIdx = 0;
            for (int t = 0; t < titleRows; t++) {
                Row titleRow = sheet.createRow(rowIdx++);
                titleRow.createCell(0).setCellValue("Cohort 2026 — Reference Export");
            }
            Row headerRow = sheet.createRow(rowIdx++);
            for (int c = 0; c < headers.size(); c++) {
                headerRow.createCell(c).setCellValue(headers.get(c));
            }
            for (List<String> data : dataRows) {
                Row row = sheet.createRow(rowIdx++);
                for (int c = 0; c < data.size(); c++) {
                    row.createCell(c).setCellValue(data.get(c));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void validate_withTitleBlockAboveHeaders_stillParsesSuccessfully() {
        byte[] specs = buildWorkbook(2,
            List.of("specializationid", "specialization"),
            List.of(List.of("SWE", "Software Engineering")));
        byte[] modules = buildWorkbook(2,
            List.of("specializationid", "moduleid", "module name"),
            List.of(List.of("SWE", "BEM01", "Backend Fundamentals")));
        byte[] labs = buildWorkbook(2,
            List.of("moduleid", "assessmentid", "lab title"),
            List.of(List.of("BEM01", "A1", "REST API Basics")));
        byte[] learners = buildWorkbook(2,
            List.of("amalitech email", "full name", "specialization"),
            List.of(List.of("ama.owusu@example.com", "Ama Owusu", "Software Engineering")));
        stubFolderContents(specs, modules, labs, learners);

        Gate3Result result = validator.validate(DRIVE_ID, REF_FOLDER_ID);

        assertThat(result.gate().passed()).isTrue();
        assertThat(result.bundle().specializations()).hasSize(1);
        assertThat(result.bundle().modules()).hasSize(1);
        assertThat(result.bundle().labs()).hasSize(1);
        assertThat(result.bundle().learners()).hasSize(1);
    }

    @Test
    void validate_withNoTitleBlock_stillParsesSuccessfully() {
        byte[] specs = buildWorkbook(0,
            List.of("specializationid", "specialization"),
            List.of(List.of("SWE", "Software Engineering")));
        byte[] modules = buildWorkbook(0,
            List.of("specializationid", "moduleid", "module name"),
            List.of(List.of("SWE", "BEM01", "Backend Fundamentals")));
        byte[] labs = buildWorkbook(0,
            List.of("moduleid", "assessmentid", "lab title"),
            List.of(List.of("BEM01", "A1", "REST API Basics")));
        byte[] learners = buildWorkbook(0,
            List.of("amalitech email", "full name", "specialization"),
            List.of(List.of("ama.owusu@example.com", "Ama Owusu", "Software Engineering")));
        stubFolderContents(specs, modules, labs, learners);

        Gate3Result result = validator.validate(DRIVE_ID, REF_FOLDER_ID);

        assertThat(result.gate().passed()).isTrue();
    }

    @Test
    void validate_withDuplicateModuleId_reportsDuplicateModuleIdError() {
        byte[] specs = buildWorkbook(0,
            List.of("specializationid", "specialization"),
            List.of(List.of("SWE", "Software Engineering")));
        byte[] modules = buildWorkbook(0,
            List.of("specializationid", "moduleid", "module name"),
            List.of(
                List.of("SWE", "BEM01", "Backend Fundamentals"),
                List.of("SWE", "BEM01", "Backend Fundamentals Duplicate")));
        byte[] labs = buildWorkbook(0,
            List.of("moduleid", "assessmentid", "lab title"),
            List.of(List.of("BEM01", "A1", "REST API Basics")));
        byte[] learners = buildWorkbook(0,
            List.of("amalitech email", "full name", "specialization"),
            List.of(List.of("ama.owusu@example.com", "Ama Owusu", "Software Engineering")));
        stubFolderContents(specs, modules, labs, learners);

        Gate3Result result = validator.validate(DRIVE_ID, REF_FOLDER_ID);

        assertThat(result.gate().passed()).isFalse();
        assertThat(result.gate().errors())
            .anyMatch(e -> "G3-DUP-MODULE-ID".equals(e.rule()));
    }

    @Test
    void validate_withDuplicateAssessmentId_reportsDuplicateAssessmentIdError() {
        byte[] specs = buildWorkbook(0,
            List.of("specializationid", "specialization"),
            List.of(List.of("SWE", "Software Engineering")));
        byte[] modules = buildWorkbook(0,
            List.of("specializationid", "moduleid", "module name"),
            List.of(List.of("SWE", "BEM01", "Backend Fundamentals")));
        byte[] labs = buildWorkbook(0,
            List.of("moduleid", "assessmentid", "lab title"),
            List.of(
                List.of("BEM01", "A1", "REST API Basics"),
                List.of("BEM01", "A1", "REST API Advanced")));
        byte[] learners = buildWorkbook(0,
            List.of("amalitech email", "full name", "specialization"),
            List.of(List.of("ama.owusu@example.com", "Ama Owusu", "Software Engineering")));
        stubFolderContents(specs, modules, labs, learners);

        Gate3Result result = validator.validate(DRIVE_ID, REF_FOLDER_ID);

        assertThat(result.gate().passed()).isFalse();
        assertThat(result.gate().errors())
            .anyMatch(e -> "G3-DUP-ASSESSMENT-ID".equals(e.rule()));
    }

    @Test
    void validate_withAmbiguousSpecializationName_reportsAmbiguousSpecNameError() {
        byte[] specs = buildWorkbook(0,
            List.of("specializationid", "specialization"),
            List.of(
                List.of("SWE-JAVA", "Software Engineering - Java"),
                List.of("SWE-JS", "Software Engineering - JS")));
        byte[] modules = buildWorkbook(0,
            List.of("specializationid", "moduleid", "module name"),
            List.of(List.of("SWE-JAVA", "BEM01", "Backend Fundamentals")));
        byte[] labs = buildWorkbook(0,
            List.of("moduleid", "assessmentid", "lab title"),
            List.of(List.of("BEM01", "A1", "REST API Basics")));
        byte[] learners = buildWorkbook(0,
            List.of("amalitech email", "full name", "specialization"),
            List.of(List.of("ama.owusu@example.com", "Ama Owusu", "Software Engineering")));
        stubFolderContents(specs, modules, labs, learners);

        Gate3Result result = validator.validate(DRIVE_ID, REF_FOLDER_ID);

        assertThat(result.gate().passed()).isFalse();
        assertThat(result.gate().errors())
            .anyMatch(e -> "G3-AMBIGUOUS-SPEC-NAME".equals(e.rule()));
    }

    @Test
    void validate_withExactSpecializationNameMatch_passesEvenWhenSubstringWouldBeAmbiguous() {
        byte[] specs = buildWorkbook(0,
            List.of("specializationid", "specialization"),
            List.of(
                List.of("SWE-JAVA", "Software Engineering - Java"),
                List.of("SWE-JS", "Software Engineering - JS")));
        byte[] modules = buildWorkbook(0,
            List.of("specializationid", "moduleid", "module name"),
            List.of(List.of("SWE-JAVA", "BEM01", "Backend Fundamentals")));
        byte[] labs = buildWorkbook(0,
            List.of("moduleid", "assessmentid", "lab title"),
            List.of(List.of("BEM01", "A1", "REST API Basics")));
        byte[] learners = buildWorkbook(0,
            List.of("amalitech email", "full name", "specialization"),
            List.of(List.of("ama.owusu@example.com", "Ama Owusu", "Software Engineering - Java")));
        stubFolderContents(specs, modules, labs, learners);

        Gate3Result result = validator.validate(DRIVE_ID, REF_FOLDER_ID);

        assertThat(result.gate().passed()).isTrue();
        assertThat(result.bundle().learners()).hasSize(1);
    }
}
