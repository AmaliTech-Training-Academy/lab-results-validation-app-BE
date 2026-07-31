package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import com.amalitech.labresultsvalidator.domain.cohort.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabResultRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * B8 — classifies each validated row as new/unchanged/changed/duplicate. Identity is
 * {@code (learnerId, labId)} — both are resolved during validation and don't change between
 * ingestion runs, unlike {@code submittedOn} (a re-grade can land on a new date). Change detection
 * (unchanged vs changed) compares {@code submittedOn}/{@code score} via {@link RowFingerprint}.
 */
@Component
public class ScoreRowClassifier {

    private final LabResultRepository labResultRepository;

    public ScoreRowClassifier(LabResultRepository labResultRepository) {
        this.labResultRepository = labResultRepository;
    }

    public List<RowClassification> classify(List<ValidatedScoreRow> validRows) {
        Map<String, List<ValidatedScoreRow>> groups = validRows.stream()
            .collect(Collectors.groupingBy(this::identityKey));
        Map<String, LabResult> existingByKey = fetchExisting(validRows);

        List<RowClassification> results = new ArrayList<>();
        for (List<ValidatedScoreRow> group : groups.values()) {
            ValidatedScoreRow first = group.get(0);
            LabResult existing = existingByKey.get(identityKey(first));
            if (group.size() > 1) {
                for (ValidatedScoreRow row : group) {
                    results.add(new RowClassification(ClassificationKind.DUPLICATE, row, existing));
                }
            } else {
                classifySingleRow(first, existing, results);
            }
        }
        return results;
    }

    /** Batch-fetches every existing row that could match this sheet's rows in one query (B8). */
    private Map<String, LabResult> fetchExisting(List<ValidatedScoreRow> validRows) {
        Set<UUID> learnerIds = validRows.stream()
            .map(ValidatedScoreRow::learnerId).collect(Collectors.toSet());
        Set<UUID> labIds = validRows.stream()
            .map(ValidatedScoreRow::labId).collect(Collectors.toSet());
        return labResultRepository.findByLearnerIdInAndLabIdIn(learnerIds, labIds).stream()
            .collect(Collectors.toMap(r -> identityKey(r.getLearnerId(), r.getLabId()), r -> r));
    }

    private void classifySingleRow(ValidatedScoreRow row, LabResult existing, List<RowClassification> results) {
        if (existing == null) {
            results.add(new RowClassification(ClassificationKind.NEW, row, null));
            return;
        }

        String incomingFingerprint = RowFingerprint.compute(row.submittedOn(), row.score());
        ClassificationKind kind = incomingFingerprint.equals(existing.getRowValueHash())
            ? ClassificationKind.UNCHANGED
            : ClassificationKind.CHANGED;
        results.add(new RowClassification(kind, row, existing));
    }

    private String identityKey(ValidatedScoreRow row) {
        return identityKey(row.learnerId(), row.labId());
    }

    private String identityKey(UUID learnerId, UUID labId) {
        return learnerId + "|" + labId;
    }
}
