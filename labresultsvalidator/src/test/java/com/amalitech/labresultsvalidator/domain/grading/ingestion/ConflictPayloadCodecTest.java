package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.domain.grading.dto.ConflictCandidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConflictPayloadCodecTest {

    private static final LocalDate SUBMITTED_ON = LocalDate.of(2026, 1, 15);

    private ValidatedScoreRow row(int rowNum, String score, UUID instructorContactId) {
        return new ValidatedScoreRow("Module 1 Grading.xlsx", "Module-1", rowNum, UUID.randomUUID(),
            UUID.randomUUID(), instructorContactId, "ama owusu", SUBMITTED_ON, new BigDecimal(score));
    }

    @Test
    void writeThenRead_roundTripsEveryCandidateInOrder() {
        String json = ConflictPayloadCodec.write(List.of(row(5, "88.00", null), row(15, "98.00", null)));

        List<ConflictCandidate> candidates = ConflictPayloadCodec.read(json);

        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(ConflictCandidate::index).containsExactly(0, 1);
        assertThat(candidates).extracting(ConflictCandidate::rowNum).containsExactly(5, 15);
        assertThat(candidates).extracting(c -> c.score().toPlainString()).containsExactly("88.00", "98.00");
        assertThat(candidates).allSatisfy(c -> {
            assertThat(c.fileName()).isEqualTo("Module 1 Grading.xlsx");
            assertThat(c.sheetName()).isEqualTo("Module-1");
            assertThat(c.submittedOn()).isEqualTo(SUBMITTED_ON);
            assertThat(c.isCommittable()).isTrue();
        });
    }

    @Test
    void read_legacySingleRowPayload_isTreatedAsOneCandidate() {
        // Written before duplicates were grouped: one conflict per copy, payload = the bare row. Those
        // rows are still in the table, so they must stay readable without a data migration.
        String legacy = "{\"fileName\":\"Module 1 Grading.xlsx\",\"sheetName\":\"Module-1\",\"rowNum\":5,"
            + "\"nspName\":\"ama owusu\",\"submittedOn\":\"2026-01-15\",\"score\":\"88.00\","
            + "\"instructorContactId\":null}";

        List<ConflictCandidate> candidates = ConflictPayloadCodec.read(legacy);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).index()).isZero();
        assertThat(candidates.get(0).score()).isEqualByComparingTo("88.00");
        assertThat(candidates.get(0).isCommittable()).isTrue();
    }

    @Test
    void read_serializationFailureSentinel_yieldsNoCandidates() {
        assertThat(ConflictPayloadCodec.read("{\"error\":\"serialization failed\"}")).isEmpty();
    }

    @Test
    void read_nullBlankOrUnparseablePayload_yieldsNoCandidates() {
        assertThat(ConflictPayloadCodec.read(null)).isEmpty();
        assertThat(ConflictPayloadCodec.read("   ")).isEmpty();
        assertThat(ConflictPayloadCodec.read("{not json")).isEmpty();
    }

    @Test
    void read_scoreStoredAsAJsonNumber_isStillParsed() {
        List<ConflictCandidate> candidates = ConflictPayloadCodec.read(
            "{\"candidates\":[{\"rowNum\":5,\"submittedOn\":\"2026-01-15\",\"score\":88}]}");

        assertThat(candidates.get(0).score()).isEqualByComparingTo("88");
        assertThat(candidates.get(0).isCommittable()).isTrue();
    }

    @Test
    void read_presentButUnparseableField_marksTheRowUncommittable() {
        // Absent is normal (an unresolved reviewer is non-blocking); present-but-garbage is corruption,
        // and committing it as if the field had been empty would rewrite a grade from a payload we
        // could not fully read.
        List<ConflictCandidate> candidates = ConflictPayloadCodec.read(
            "{\"candidates\":[{\"rowNum\":5,\"submittedOn\":\"2026-01-15\",\"score\":\"88.00\","
                + "\"instructorContactId\":\"not-a-uuid\"}]}");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).instructorContactId()).isNull();
        assertThat(candidates.get(0).payloadIntact()).isFalse();
        assertThat(candidates.get(0).isCommittable()).isFalse();
    }

    @Test
    void read_missingOptionalReviewer_leavesTheRowCommittable() {
        List<ConflictCandidate> candidates = ConflictPayloadCodec.read(
            "{\"candidates\":[{\"rowNum\":5,\"submittedOn\":\"2026-01-15\",\"score\":\"88.00\","
                + "\"instructorContactId\":null}]}");

        assertThat(candidates.get(0).payloadIntact()).isTrue();
        assertThat(candidates.get(0).isCommittable()).isTrue();
    }

    @Test
    void write_preservesTheResolvedReviewerId() {
        UUID reviewerId = UUID.randomUUID();

        List<ConflictCandidate> candidates =
            ConflictPayloadCodec.read(ConflictPayloadCodec.write(List.of(row(5, "88.00", reviewerId))));

        assertThat(candidates.get(0).instructorContactId()).isEqualTo(reviewerId);
    }
}
