package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateCandidateFingerprintTest {

    private static final LocalDate SUBMITTED_ON = LocalDate.of(2026, 1, 15);
    private static final UUID LEARNER_ID = UUID.randomUUID();
    private static final UUID LAB_ID = UUID.randomUUID();

    private ValidatedScoreRow row(int rowNum, String score) {
        return new ValidatedScoreRow("Module 1 Grading.xlsx", "Module-1", rowNum, LEARNER_ID, LAB_ID,
            null, "ama owusu", SUBMITTED_ON, new BigDecimal(score));
    }

    @Test
    void incomingRowsAndTheirStoredPayload_produceTheSameDigest() {
        // The whole point: the digest computed while ingesting must equal the one computed later from
        // what was stored, or "has this duplicate changed?" can never answer no.
        List<ValidatedScoreRow> rows = List.of(row(5, "88.00"), row(15, "98.00"));

        String fromRows = DuplicateCandidateFingerprint.ofRows(rows);
        String fromStored = DuplicateCandidateFingerprint.ofCandidates(
            ConflictPayloadCodec.read(ConflictPayloadCodec.write(rows)));

        assertThat(fromRows).isEqualTo(fromStored).hasSize(64);
    }

    @Test
    void reorderingTheCopies_doesNotChangeTheDigest() {
        assertThat(DuplicateCandidateFingerprint.ofRows(List.of(row(5, "88.00"), row(15, "98.00"))))
            .isEqualTo(DuplicateCandidateFingerprint.ofRows(List.of(row(15, "98.00"), row(5, "88.00"))));
    }

    @Test
    void aChangedMark_changesTheDigest() {
        assertThat(DuplicateCandidateFingerprint.ofRows(List.of(row(5, "88.00"), row(15, "98.00"))))
            .isNotEqualTo(DuplicateCandidateFingerprint.ofRows(List.of(row(5, "88.00"), row(15, "70.00"))));
    }

    @Test
    void aChangedSheetRow_changesTheDigest() {
        assertThat(DuplicateCandidateFingerprint.ofRows(List.of(row(5, "88.00"), row(15, "98.00"))))
            .isNotEqualTo(DuplicateCandidateFingerprint.ofRows(List.of(row(5, "88.00"), row(16, "98.00"))));
    }

    @Test
    void anExtraCopy_changesTheDigest() {
        assertThat(DuplicateCandidateFingerprint.ofRows(List.of(row(5, "88.00"), row(15, "98.00"))))
            .isNotEqualTo(DuplicateCandidateFingerprint.ofRows(
                List.of(row(5, "88.00"), row(15, "98.00"), row(20, "60.00"))));
    }

    @Test
    void scoreScale_doesNotChangeTheDigest() {
        // Stored payloads carry both "88" and "88.00" depending on when they were written; the same
        // mark must not read as a change.
        assertThat(DuplicateCandidateFingerprint.ofRows(List.of(row(5, "88"))))
            .isEqualTo(DuplicateCandidateFingerprint.ofRows(List.of(row(5, "88.00"))));
    }
}
