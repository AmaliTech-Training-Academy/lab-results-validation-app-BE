package com.amalitech.labresultsvalidator.domain.grading.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConflictRemediationTest {

    private ConflictCandidate candidate(int index, String fileName, String sheetName, Integer rowNum) {
        return new ConflictCandidate(index, fileName, sheetName, rowNum, "ama owusu",
            new BigDecimal("88.00"), LocalDate.of(2026, 1, 15), null, null, true);
    }

    @Test
    void twoRowsInOneSheet_namesTheFileSheetAndBothRows() {
        String remediation = ConflictRemediation.describe(List.of(
            candidate(0, "Module 1 Grading.xlsx", "Module-1", 5),
            candidate(1, "Module 1 Grading.xlsx", "Module-1", 15)));

        assertThat(remediation)
            .contains("in Module 1 Grading.xlsx, sheet Module-1, rows 5 and 15")
            .contains("Remove the extra row there to fix it permanently");
    }

    @Test
    void threeRows_readsAsAList() {
        String remediation = ConflictRemediation.describe(List.of(
            candidate(0, "Module 1 Grading.xlsx", "Module-1", 5),
            candidate(1, "Module 1 Grading.xlsx", "Module-1", 15),
            candidate(2, "Module 1 Grading.xlsx", "Module-1", 20)));

        assertThat(remediation).contains("rows 5, 15 and 20");
    }

    @Test
    void copiesSpanningTwoFiles_namesBoth() {
        String remediation = ConflictRemediation.describe(List.of(
            candidate(0, "Module 1 Grading.xlsx", "Module-1", 5),
            candidate(1, "Module 1 Regrade.xlsx", "Module-1", 9)));

        assertThat(remediation).contains("across Module 1 Grading.xlsx and Module 1 Regrade.xlsx");
    }

    @Test
    void doesNotClaimTheDecisionOnlyAppliesToThisRun() {
        // The duplicate is not re-raised while its rows and marks are unchanged, so promising the
        // admin that resolving "applies to this run only" would be untrue.
        String remediation = ConflictRemediation.describe(List.of(
            candidate(0, "Module 1 Grading.xlsx", "Module-1", 5),
            candidate(1, "Module 1 Grading.xlsx", "Module-1", 15)));

        assertThat(remediation)
            .doesNotContain("this run only")
            .contains("only raised again if its rows or marks change");
    }

    @Test
    void nothingLocatable_yieldsNoSentenceRatherThanAMisleadingOne() {
        assertThat(ConflictRemediation.describe(List.of())).isNull();
        assertThat(ConflictRemediation.describe(null)).isNull();
        assertThat(ConflictRemediation.describe(List.of(candidate(0, null, null, null)))).isNull();
        assertThat(ConflictRemediation.describe(List.of(candidate(0, "Module 1 Grading.xlsx", "Module-1", null))))
            .isNull();
    }
}
