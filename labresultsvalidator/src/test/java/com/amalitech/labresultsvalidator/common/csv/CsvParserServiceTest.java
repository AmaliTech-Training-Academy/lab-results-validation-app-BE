package com.amalitech.labresultsvalidator.common.csv;

import com.amalitech.labresultsvalidator.domain.learner.dto.LearnerCsvRow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvParserServiceTest {

    private static final String CONTENT_TYPE_CSV = "text/csv";

    private final CsvParserService csvParserService = new CsvParserService();

    // --- file validation ---

    @Test
    void parse_withNullFile_throwsMalformedCsvException() {
        assertThatThrownBy(() -> csvParserService.parse(null, LearnerCsvRow.class))
                .isInstanceOf(MalformedCsvException.class)
                .hasMessageContaining("empty or missing");
    }

    @Test
    void parse_withEmptyFile_throwsMalformedCsvException() {
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", CONTENT_TYPE_CSV, new byte[0]);

        assertThatThrownBy(() -> csvParserService.parse(file, LearnerCsvRow.class))
                .isInstanceOf(MalformedCsvException.class)
                .hasMessageContaining("empty or missing");
    }

    @Test
    void parse_withDisallowedContentType_throwsMalformedCsvException() {
        byte[] content = "FULL_NAME,EMAIL\nAlice,a@test.com".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", content);

        assertThatThrownBy(() -> csvParserService.parse(file, LearnerCsvRow.class))
                .isInstanceOf(MalformedCsvException.class)
                .hasMessageContaining("Unsupported content type");
    }

    @Test
    void parse_withCsvFileContainingOnlyHeader_producesEmptyResult() {
        String csv = "FULL_NAME,EMAIL,COHORT_NAME,SPECIALIZATION_NAME";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", CONTENT_TYPE_CSV,
                csv.getBytes(StandardCharsets.UTF_8));

        CsvParseResult<LearnerCsvRow> result = csvParserService.parse(file, LearnerCsvRow.class);

        assertThat(result.validRows()).isEmpty();
        assertThat(result.errors()).isEmpty();
        assertThat(result.totalRows()).isZero();
    }

    // --- header validation ---

    @Test
    void parse_whenRequiredColumnMissing_throwsMalformedCsvException() {
        String csv = "FULL_NAME,EMAIL,COHORT_NAME\nAlice,a@test.com,Cohort1";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", CONTENT_TYPE_CSV,
                csv.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> csvParserService.parse(file, LearnerCsvRow.class))
                .isInstanceOf(MalformedCsvException.class)
                .hasMessageContaining("SPECIALIZATION_NAME");
    }

    @Test
    void parse_whenMultipleRequiredColumnsMissing_listsAllMissingInMessage() {
        String csv = "FULL_NAME,EMAIL\nAlice,a@test.com";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", CONTENT_TYPE_CSV,
                csv.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> csvParserService.parse(file, LearnerCsvRow.class))
                .isInstanceOf(MalformedCsvException.class)
                .hasMessageContaining("COHORT_NAME")
                .hasMessageContaining("SPECIALIZATION_NAME");
    }

    // --- successful parse ---

    @Test
    void parse_withValidCsv_returnsAllRowsBound() {
        String csv = "FULL_NAME,EMAIL,COHORT_NAME,SPECIALIZATION_NAME\n"
                + "Alice Doe,alice@test.com,Cohort A,Backend\n"
                + "Bob Smith,bob@test.com,Cohort A,Frontend";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", CONTENT_TYPE_CSV,
                csv.getBytes(StandardCharsets.UTF_8));

        CsvParseResult<LearnerCsvRow> result = csvParserService.parse(file, LearnerCsvRow.class);

        assertThat(result.validRows()).hasSize(2);
        assertThat(result.errors()).isEmpty();
        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(result.rejectedCount()).isZero();
    }

    @Test
    void parse_assignsCorrectPhysicalLineNumbers() {
        String csv = "FULL_NAME,EMAIL,COHORT_NAME,SPECIALIZATION_NAME\n"
                + "Alice,alice@test.com,C1,S1\n"
                + "Bob,bob@test.com,C1,S1";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", CONTENT_TYPE_CSV,
                csv.getBytes(StandardCharsets.UTF_8));

        CsvParseResult<LearnerCsvRow> result = csvParserService.parse(file, LearnerCsvRow.class);

        List<ParsedRow<LearnerCsvRow>> rows = result.validRows();
        assertThat(rows.get(0).lineNumber()).isEqualTo(2L);
        assertThat(rows.get(1).lineNumber()).isEqualTo(3L);
    }

    @Test
    void parse_capturesRawCellsByLineForEveryDataRow() {
        String csv = "FULL_NAME,EMAIL,COHORT_NAME,SPECIALIZATION_NAME\n"
                + "Alice,alice@test.com,C1,S1\n"
                + "Bob,bob@test.com,C2,S2";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", CONTENT_TYPE_CSV,
                csv.getBytes(StandardCharsets.UTF_8));

        CsvParseResult<LearnerCsvRow> result = csvParserService.parse(file, LearnerCsvRow.class);

        assertThat(result.rawCellsByLine()).containsOnlyKeys(2L, 3L);
        assertThat(result.rawCellsByLine().get(2L))
                .containsEntry("FULL_NAME", "Alice")
                .containsEntry("EMAIL", "alice@test.com")
                .containsEntry("COHORT_NAME", "C1")
                .containsEntry("SPECIALIZATION_NAME", "S1");
        assertThat(result.rawCellsByLine().get(3L)).containsEntry("FULL_NAME", "Bob");
    }

    @Test
    void parse_withNullContentType_isAccepted() {
        String csv = "FULL_NAME,EMAIL,COHORT_NAME,SPECIALIZATION_NAME\nAlice,a@test.com,C1,S1";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", null,
                csv.getBytes(StandardCharsets.UTF_8));

        CsvParseResult<LearnerCsvRow> result = csvParserService.parse(file, LearnerCsvRow.class);

        assertThat(result.validRows()).hasSize(1);
    }

    @Test
    void parse_headerColumnsAreCaseInsensitive() {
        String csv = "full_name,email,cohort_name,specialization_name\nAlice,a@test.com,C1,S1";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", CONTENT_TYPE_CSV,
                csv.getBytes(StandardCharsets.UTF_8));

        CsvParseResult<LearnerCsvRow> result = csvParserService.parse(file, LearnerCsvRow.class);

        assertThat(result.validRows()).hasSize(1);
    }

    @Test
    void parse_bindsFieldValuesCorrectly() {
        String csv = "FULL_NAME,EMAIL,COHORT_NAME,SPECIALIZATION_NAME\nJane Doe,jane@test.com,Cohort X,DevOps";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", CONTENT_TYPE_CSV,
                csv.getBytes(StandardCharsets.UTF_8));

        CsvParseResult<LearnerCsvRow> result = csvParserService.parse(file, LearnerCsvRow.class);

        LearnerCsvRow row = result.validRows().get(0).data();
        assertThat(row.getFullName()).isEqualTo("Jane Doe");
        assertThat(row.getEmail()).isEqualTo("jane@test.com");
        assertThat(row.getCohortName()).isEqualTo("Cohort X");
        assertThat(row.getSpecializationName()).isEqualTo("DevOps");
    }

    @Test
    void parse_whenRequiredFieldValueIsEmpty_errorHasCorrectFieldAndRule() {
        // EMAIL is required=true; leaving it blank triggers CsvRequiredFieldEmptyException
        String csv = "FULL_NAME,EMAIL,COHORT_NAME,SPECIALIZATION_NAME\nAlice,,Cohort A,Backend";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", CONTENT_TYPE_CSV,
                csv.getBytes(StandardCharsets.UTF_8));

        CsvParseResult<LearnerCsvRow> result = csvParserService.parse(file, LearnerCsvRow.class);

        assertThat(result.errors()).hasSize(1);
        CsvRowError error = result.errors().get(0);
        assertThat(error.field()).isEqualTo("EMAIL");
        assertThat(error.rule()).isEqualTo("V3");
        assertThat(error.rowNumber()).isEqualTo(2L);
    }

    @Test
    void parse_whenMultipleRequiredFieldValuesEmpty_eachErrorHasFieldAndRule() {
        // First row: EMAIL empty; second row: COHORT_NAME empty
        String csv = "FULL_NAME,EMAIL,COHORT_NAME,SPECIALIZATION_NAME\n"
                + "Alice,,Cohort A,Backend\n"
                + "Bob,bob@test.com,,Backend";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", CONTENT_TYPE_CSV,
                csv.getBytes(StandardCharsets.UTF_8));

        CsvParseResult<LearnerCsvRow> result = csvParserService.parse(file, LearnerCsvRow.class);

        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors()).allSatisfy(e -> {
            assertThat(e.rule()).isEqualTo("V3");
            assertThat(e.field()).isNotNull();
        });
        assertThat(result.errors()).extracting(CsvRowError::field)
                .containsExactlyInAnyOrder("EMAIL", "COHORT_NAME");
    }
}
