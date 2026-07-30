package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A row that passed F1-F3/R1/R4 validation, with all reference-data lookups resolved.
 * {@code instructorContactId} may be null (unresolved reviewer is non-blocking, B6 AC4).
 * {@code nspName} is normalized (trim + lowercase) — the actual matching identity for
 * classification (B8), alongside {@code submittedOn}.
 */
public record ValidatedScoreRow(
    String fileName,
    String sheetName,
    int rowNum,
    UUID learnerId,
    UUID labId,
    UUID instructorContactId,
    String nspName,
    LocalDate submittedOn,
    BigDecimal score
) {

    public String location() {
        return "sheet " + sheetName + " row " + rowNum;
    }
}
