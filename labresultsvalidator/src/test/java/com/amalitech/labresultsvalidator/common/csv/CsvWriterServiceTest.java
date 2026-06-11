package com.amalitech.labresultsvalidator.common.csv;

import com.amalitech.labresultsvalidator.domain.lab_result.dto.LabResultCsvRow;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

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
}
