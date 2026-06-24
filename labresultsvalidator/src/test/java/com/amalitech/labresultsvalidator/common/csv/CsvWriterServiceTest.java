package com.amalitech.labresultsvalidator.common.csv;

import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultCorrectionRow;
import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultCsvRow;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterServiceTest {

    private final CsvWriterService service = new CsvWriterService();

    @Test
    void writeTemplate_emitsColumnsInFieldDeclarationOrder() {
        StringWriter out = new StringWriter();

        service.writeTemplate(out, LabResultCsvRow.class);

        String header = out.toString().split("\\R")[0].replace("\"", "");
        assertThat(header.split(",")).containsExactly(
            "LEARNER_EMAIL", "COHORT_NAME", "SPECIALIZATION_NAME", "MODULE_NAME", "LAB_TITLE",
            "SCORE", "MAX_SCORE", "ATTEMPT_NUMBER", "SUBMITTED_ON", "GRADED_BY");
    }

    @Test
    void write_emitsAllFieldValuesInDeclarationOrder() {
        StringWriter out = new StringWriter();
        LabResultCorrectionRow row = LabResultCorrectionRow.builder()
            .learnerEmail("jane@test.com").cohortName("Cohort 1").specializationName("Data Analytics")
            .moduleName("Module 1").labTitle("Lab 1").score("18").maxScore("20")
            .attemptNumber("1").submittedOn("2026-05-30").gradedBy("Dr. Smith")
            .errorMessage("SCORE: out of range")
            .build();

        service.write(out, List.of(row), LabResultCorrectionRow.class);

        String[] lines = out.toString().split("\\R");
        String header = lines[0].replace("\"", "");
        String data = lines[1].replace("\"", "");
        assertThat(header.split(",")).containsExactly(
            "LEARNER_EMAIL", "COHORT_NAME", "SPECIALIZATION_NAME", "MODULE_NAME", "LAB_TITLE",
            "SCORE", "MAX_SCORE", "ATTEMPT_NUMBER", "SUBMITTED_ON", "GRADED_BY", "ERROR_MESSAGE");
        assertThat(data.split(",")).containsExactly(
            "jane@test.com", "Cohort 1", "Data Analytics", "Module 1", "Lab 1",
            "18", "20", "1", "2026-05-30", "Dr. Smith", "SCORE: out of range");
    }
}
