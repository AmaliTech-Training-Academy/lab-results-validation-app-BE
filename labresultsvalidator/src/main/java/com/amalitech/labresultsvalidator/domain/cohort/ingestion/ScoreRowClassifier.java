package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import com.amalitech.labresultsvalidator.domain.cohort.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabResultRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * B8 — classifies each validated row as new/unchanged/changed/duplicate. Identity is
 * {@code (submittedOn, nspName)}, not {@code learnerId}/{@code labId} (finalized model —
 * supersedes what's currently written in the PRD).
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

        List<RowClassification> results = new ArrayList<>();
        for (List<ValidatedScoreRow> group : groups.values()) {
            if (group.size() > 1) {
                classifyDuplicateGroup(group, results);
            } else {
                classifySingleRow(group.get(0), results);
            }
        }
        return results;
    }

    private void classifyDuplicateGroup(List<ValidatedScoreRow> group, List<RowClassification> results) {
        ValidatedScoreRow first = group.get(0);
        LabResult existing = labResultRepository
            .findBySubmittedOnAndNspName(first.submittedOn(), first.nspName())
            .orElse(null);
        for (ValidatedScoreRow row : group) {
            results.add(new RowClassification(ClassificationKind.DUPLICATE, row, existing));
        }
    }

    private void classifySingleRow(ValidatedScoreRow row, List<RowClassification> results) {
        Optional<LabResult> existingOpt = labResultRepository
            .findBySubmittedOnAndNspName(row.submittedOn(), row.nspName());

        if (existingOpt.isEmpty()) {
            results.add(new RowClassification(ClassificationKind.NEW, row, null));
            return;
        }

        LabResult existing = existingOpt.get();
        String incomingFingerprint = RowFingerprint.compute(row.submittedOn(), row.nspName(), row.score());
        ClassificationKind kind = incomingFingerprint.equals(existing.getRowValueHash())
            ? ClassificationKind.UNCHANGED
            : ClassificationKind.CHANGED;
        results.add(new RowClassification(kind, row, existing));
    }

    private String identityKey(ValidatedScoreRow row) {
        return row.submittedOn() + "|" + row.nspName();
    }
}
