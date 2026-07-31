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

    @BeforeEach
    void setUp() {
        classifier = new ScoreRowClassifier(labResultRepository);
    }

    private ValidatedScoreRow row(BigDecimal score) {
        return new ValidatedScoreRow("Instructor1.xlsx", "BEM01", 2, UUID.randomUUID(), UUID.randomUUID(),
            null, NSP_NAME, SUBMITTED_ON, score);
    }

    private void stubExisting(LabResult... existing) {
        when(labResultRepository.findBySubmittedOnInAndNspNameIn(Set.of(SUBMITTED_ON), Set.of(NSP_NAME)))
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
        LabResult existing = LabResult.builder().id(UUID.randomUUID())
            .rowValueHash(RowFingerprint.compute(SUBMITTED_ON, NSP_NAME, score))
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
        LabResult existing = LabResult.builder().id(UUID.randomUUID())
            .rowValueHash(RowFingerprint.compute(SUBMITTED_ON, NSP_NAME, oldScore))
            .score(oldScore).submittedOn(SUBMITTED_ON).nspName(NSP_NAME).build();
        stubExisting(existing);

        List<RowClassification> result = classifier.classify(List.of(row(newScore)));

        assertThat(result.get(0).kind()).isEqualTo(ClassificationKind.CHANGED);
        assertThat(result.get(0).existing()).isEqualTo(existing);
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
        LabResult existing = LabResult.builder().id(UUID.randomUUID())
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
