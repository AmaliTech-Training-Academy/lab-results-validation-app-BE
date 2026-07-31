package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import com.amalitech.labresultsvalidator.domain.cohort.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScoreRowClassifierTest {

    @Mock
    private LabResultRepository labResultRepository;

    private ScoreRowClassifier classifier;

    private static final LocalDate SUBMITTED_ON = LocalDate.of(2026, 1, 15);
    private static final String NSP_NAME = "ama owusu";
    private static final UUID LEARNER_ID = UUID.randomUUID();
    private static final UUID LAB_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        classifier = new ScoreRowClassifier(labResultRepository);
    }

    private ValidatedScoreRow row(BigDecimal score) {
        return row(score, SUBMITTED_ON);
    }

    private ValidatedScoreRow row(BigDecimal score, LocalDate submittedOn) {
        return new ValidatedScoreRow("Instructor1.xlsx", "BEM01", 2, LEARNER_ID, LAB_ID,
            null, NSP_NAME, submittedOn, score);
    }

    private void stubExisting(LabResult... existing) {
        when(labResultRepository.findByLearnerIdInAndLabIdIn(Set.of(LEARNER_ID), Set.of(LAB_ID)))
            .thenReturn(List.of(existing));
    }

    @Test
    void classify_noExistingRecord_isNew() {
        stubExisting();

        List<RowClassification> result = classifier.classify(List.of(row(new BigDecimal("90.00"))));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).kind()).isEqualTo(ClassificationKind.NEW);
        assertThat(result.get(0).existing()).isNull();
    }

    @Test
    void classify_existingRecordWithMatchingFingerprint_isUnchanged() {
        BigDecimal score = new BigDecimal("90.00");
        LabResult existing = LabResult.builder().id(UUID.randomUUID()).learnerId(LEARNER_ID).labId(LAB_ID)
            .rowValueHash(RowFingerprint.compute(SUBMITTED_ON, score))
            .score(score).submittedOn(SUBMITTED_ON).nspName(NSP_NAME).build();
        stubExisting(existing);

        List<RowClassification> result = classifier.classify(List.of(row(score)));

        assertThat(result.get(0).kind()).isEqualTo(ClassificationKind.UNCHANGED);
        assertThat(result.get(0).existing()).isEqualTo(existing);
    }

    @Test
    void classify_existingRecordWithDifferentScore_isChanged() {
        BigDecimal oldScore = new BigDecimal("85.00");
        BigDecimal newScore = new BigDecimal("90.00");
        LabResult existing = LabResult.builder().id(UUID.randomUUID()).learnerId(LEARNER_ID).labId(LAB_ID)
            .rowValueHash(RowFingerprint.compute(SUBMITTED_ON, oldScore))
            .score(oldScore).submittedOn(SUBMITTED_ON).nspName(NSP_NAME).build();
        stubExisting(existing);

        List<RowClassification> result = classifier.classify(List.of(row(newScore)));

        assertThat(result.get(0).kind()).isEqualTo(ClassificationKind.CHANGED);
        assertThat(result.get(0).existing()).isEqualTo(existing);
    }

    @Test
    void classify_regradeOnANewDate_matchesByLearnerAndLabAndIsChanged() {
        BigDecimal score = new BigDecimal("90.00");
        LabResult existing = LabResult.builder().id(UUID.randomUUID()).learnerId(LEARNER_ID).labId(LAB_ID)
            .rowValueHash(RowFingerprint.compute(SUBMITTED_ON, score))
            .score(score).submittedOn(SUBMITTED_ON).nspName(NSP_NAME).build();
        stubExisting(existing);

        LocalDate regradeDate = SUBMITTED_ON.plusDays(7);
        List<RowClassification> result = classifier.classify(List.of(row(score, regradeDate)));

        assertThat(result.get(0).kind()).isEqualTo(ClassificationKind.CHANGED);
        assertThat(result.get(0).existing()).isEqualTo(existing);
    }

    @Test
    void classify_twoDifferentLabsSameTraineeSameDate_areNotTreatedAsDuplicates() {
        UUID otherLabId = UUID.randomUUID();
        ValidatedScoreRow first = row(new BigDecimal("90.00"));
        ValidatedScoreRow second = new ValidatedScoreRow("Instructor1.xlsx", "BEM02", 3, LEARNER_ID, otherLabId,
            null, NSP_NAME, SUBMITTED_ON, new BigDecimal("95.00"));
        when(labResultRepository.findByLearnerIdInAndLabIdIn(Set.of(LEARNER_ID), Set.of(LAB_ID, otherLabId)))
            .thenReturn(List.of());

        List<RowClassification> result = classifier.classify(List.of(first, second));

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(c -> assertThat(c.kind()).isEqualTo(ClassificationKind.NEW));
    }

    @Test
    void classify_sameIdentityTwiceInOneFile_bothAreDuplicates() {
        stubExisting();

        ValidatedScoreRow first = row(new BigDecimal("90.00"));
        ValidatedScoreRow second = row(new BigDecimal("95.00"));

        List<RowClassification> result = classifier.classify(List.of(first, second));

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(c -> assertThat(c.kind()).isEqualTo(ClassificationKind.DUPLICATE));
    }

    @Test
    void classify_duplicateGroupWithAnAlreadyCommittedRecord_attachesItToEachDuplicate() {
        LabResult existing = LabResult.builder().id(UUID.randomUUID()).learnerId(LEARNER_ID).labId(LAB_ID)
            .rowValueHash("irrelevant").score(new BigDecimal("80.00")).submittedOn(SUBMITTED_ON)
            .nspName(NSP_NAME).build();
        stubExisting(existing);

        List<RowClassification> result = classifier.classify(
            List.of(row(new BigDecimal("90.00")), row(new BigDecimal("95.00"))));

        assertThat(result).allSatisfy(c -> {
            assertThat(c.kind()).isEqualTo(ClassificationKind.DUPLICATE);
            assertThat(c.existing()).isEqualTo(existing);
        });
    }
}
