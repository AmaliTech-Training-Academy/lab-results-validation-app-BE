package com.amalitech.labresultsvalidator.domain.standup.gate;

import com.amalitech.labresultsvalidator.domain.reference.entity.Lab;
import com.amalitech.labresultsvalidator.domain.reference.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.reference.entity.Learner;
import com.amalitech.labresultsvalidator.domain.reference.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.SpecializationRepository;
import com.amalitech.labresultsvalidator.domain.standup.service.Gate4EventService;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Gate4ScoreSheetValidatorTest {

    private static final String DRIVE_ID = "drive-1";
    private static final String SCORES_FOLDER_ID = "scores-folder-1";
    private static final String FILE_NAME = "Instructor1.xlsx";

    @Mock
    private GraphDriveService graphDriveService;

    @Mock
    private LearnerRepository learnerRepository;

    @Mock
    private SpecializationRepository specializationRepository;

    @Mock
    private LabModuleRepository labModuleRepository;

    @Mock
    private LabRepository labRepository;

    @Mock
    private Gate4EventService gate4EventService;

    private Gate4ScoreSheetValidator validator;

    private UUID cohortId;
    private UUID sweSpecId;
    private UUID dataSpecId;
    private UUID moduleId;

    @BeforeEach
    void setUp() {
        validator = new Gate4ScoreSheetValidator(
            graphDriveService, learnerRepository, specializationRepository, labModuleRepository, labRepository);

        cohortId = UUID.randomUUID();
        sweSpecId = UUID.randomUUID();
        dataSpecId = UUID.randomUUID();
        moduleId = UUID.randomUUID();

        Specialization swe = Specialization.builder().id(sweSpecId).cohortId(cohortId)
            .name("Software Engineering").code("SWE").build();
        Specialization data = Specialization.builder().id(dataSpecId).cohortId(cohortId)
            .name("Data Engineering").code("DE").build();
        when(specializationRepository.findAllByCohortId(cohortId)).thenReturn(List.of(swe, data));

        LabModule module = LabModule.builder().id(moduleId).specializationId(sweSpecId)
            .name("Backend Fundamentals").code("BEM01").build();
        when(labModuleRepository.findAllBySpecializationIdIn(any())).thenReturn(List.of(module));

        Lab lab = Lab.builder().id(UUID.randomUUID()).moduleId(moduleId).title("REST API Basics").build();
        when(labRepository.findAllByModuleIdIn(any())).thenReturn(List.of(lab));

        Learner sweLearner = Learner.builder().id(UUID.randomUUID()).learnerId("ama.owusu@example.com")
            .fullName("Ama Owusu").email("ama.owusu@example.com").cohortId(cohortId)
            .specializationId(sweSpecId).build();
        Learner dataLearner = Learner.builder().id(UUID.randomUUID()).learnerId("kwame.boateng@example.com")
            .fullName("Kwame Boateng").email("kwame.boateng@example.com").cohortId(cohortId)
            .specializationId(dataSpecId).build();
        when(learnerRepository.findAllByCohortId(cohortId)).thenReturn(List.of(sweLearner, dataLearner));
    }

    private void stubScoreFile(byte[] bytes) {
        List<DriveItemInfo> children = List.of(
            new DriveItemInfo(DRIVE_ID, "item-1", FILE_NAME, false, "site-1"));
        when(graphDriveService.listChildren(DRIVE_ID, SCORES_FOLDER_ID)).thenReturn(children);
        when(graphDriveService.downloadFile(DRIVE_ID, "item-1")).thenReturn(bytes);
    }

    private byte[] buildScoreWorkbook(String sheetName, int titleRows, String nspName, String labTitle,
                                       String totalScore) {
        List<String> headers = List.of("Review Date", "Name of NSP", "Lab Title", "Total Score", "Reviewer");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);
            int rowIdx = 0;
            for (int t = 0; t < titleRows; t++) {
                Row titleRow = sheet.createRow(rowIdx++);
                titleRow.createCell(0).setCellValue("Cohort 2026 — Score Sheet");
            }
            Row headerRow = sheet.createRow(rowIdx++);
            for (int c = 0; c < headers.size(); c++) {
                headerRow.createCell(c).setCellValue(headers.get(c));
            }
            Row dataRow = sheet.createRow(rowIdx);
            dataRow.createCell(0).setCellValue("2026-01-15");
            dataRow.createCell(1).setCellValue(nspName);
            dataRow.createCell(2).setCellValue(labTitle);
            dataRow.createCell(3).setCellValue(totalScore == null ? "" : totalScore);
            dataRow.createCell(4).setCellValue("Kofi Mensah");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void validate_emptyScoreState_passesWithoutRequiringAScore() {
        byte[] bytes = buildScoreWorkbook("BEM01", 0, "Ama Owusu", "REST API Basics", null);
        stubScoreFile(bytes);

        Gate4Result result = validator.validate(DRIVE_ID, SCORES_FOLDER_ID, cohortId, UUID.randomUUID(), gate4EventService);

        assertThat(result.gate().passed()).isTrue();
    }

    @Test
    void validate_withTitleBlockAboveHeaders_stillDetectsHeaderRow() {
        byte[] bytes = buildScoreWorkbook("BEM01", 2, "Ama Owusu", "REST API Basics", null);
        stubScoreFile(bytes);

        Gate4Result result = validator.validate(DRIVE_ID, SCORES_FOLDER_ID, cohortId, UUID.randomUUID(), gate4EventService);

        assertThat(result.gate().passed()).isTrue();
    }

    @Test
    void validate_unknownLabTitle_reportsUnknownLabError() {
        byte[] bytes = buildScoreWorkbook("BEM01", 0, "Ama Owusu", "Nonexistent Lab", null);
        stubScoreFile(bytes);

        Gate4Result result = validator.validate(DRIVE_ID, SCORES_FOLDER_ID, cohortId, UUID.randomUUID(), gate4EventService);

        assertThat(result.gate().passed()).isFalse();
        assertThat(result.gate().errors()).anyMatch(e -> "G4-UNKNOWN-LAB".equals(e.rule()));
    }

    @Test
    void validate_learnerWrongSpecialization_reportsSpecMismatchError() {
        // Kwame Boateng is Data Engineering, but "REST API Basics" is configured under Software Engineering.
        byte[] bytes = buildScoreWorkbook("BEM01", 0, "Kwame Boateng", "REST API Basics", null);
        stubScoreFile(bytes);

        Gate4Result result = validator.validate(DRIVE_ID, SCORES_FOLDER_ID, cohortId, UUID.randomUUID(), gate4EventService);

        assertThat(result.gate().passed()).isFalse();
        assertThat(result.gate().errors()).anyMatch(e -> "G4-SPEC-MISMATCH".equals(e.rule()));
    }

    @Test
    void validate_unknownNsp_reportsUnknownNspError() {
        byte[] bytes = buildScoreWorkbook("BEM01", 0, "Not A Learner", "REST API Basics", null);
        stubScoreFile(bytes);

        Gate4Result result = validator.validate(DRIVE_ID, SCORES_FOLDER_ID, cohortId, UUID.randomUUID(), gate4EventService);

        assertThat(result.gate().passed()).isFalse();
        assertThat(result.gate().errors()).anyMatch(e -> "G4-UNKNOWN-NSP".equals(e.rule()));
    }
}
