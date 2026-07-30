package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single non-blank data row as read from a score sheet, before any lookup/validation.
 * {@code sheetName} doubles as the module code (B5 AC1/S1). Raw text fields are kept alongside
 * their parsed counterparts (null when unparseable) so validation can report precise messages.
 */
public record ParsedScoreRow(
    String fileName,
    String sheetName,
    int rowNum,
    String reviewDateRaw,
    LocalDate reviewDate,
    String nspName,
    String labTitle,
    String totalScoreRaw,
    BigDecimal totalScore,
    String reviewer
) {

    public String location() {
        return "sheet " + sheetName + " row " + rowNum;
    }
}
