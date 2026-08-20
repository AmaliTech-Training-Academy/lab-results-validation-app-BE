package com.amalitech.labresultsvalidator.domain.grading.dto;

import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionConflict;
import com.amalitech.labresultsvalidator.domain.grading.ingestion.ConflictPayloadCodec;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A held in-file duplicate awaiting manual resolution (B10) — the REST view of
 * {@code IngestionConflict}.
 *
 * <p>Carries what an admin needs in order to decide: the learner and lab by <em>name</em>, the
 * currently stored grade, and every conflicting incoming row with its sheet row, mark and review date
 * (B10 AC1's merge-style comparison). Before this, the queue exposed {@code learnerId}/{@code labId}/
 * {@code existingResultId} as bare UUIDs with the marks buried in {@code incomingPayload} — a reviewer
 * could pick between two scores without either number being on screen.
 *
 * <p>{@code candidates} is the list to choose from; {@code ResolveConflictRequest.chosenRowIndex}
 * refers to {@link ConflictCandidate#index()}. {@code incomingPayload} is kept as the verbatim stored
 * column so existing clients (and a raw-JSON details toggle) keep working.
 *
 * <p>{@code learnerName}, {@code labTitle}, {@code existingResult} and each candidate's
 * {@code reviewerName} require reference-data lookups, so they are filled in by
 * {@code IngestionConflictViewAssembler} — {@link #from} leaves them null.
 */
public record IngestionConflictResponse(
    UUID id,
    UUID ingestionRunId,
    UUID cohortId,
    UUID learnerId,
    String learnerName,
    UUID labId,
    String labTitle,
    String conflictKind,
    UUID existingResultId,
    ExistingResultView existingResult,
    List<ConflictCandidate> candidates,
    Map<String, Object> incomingPayload,
    String remediation,
    String status,
    UUID resolvedBy,
    OffsetDateTime resolvedAt,
    String resolutionNote,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {

    // cohortId isn't stored on IngestionConflict (see its javadoc) — every caller already has it
    // in scope (it's the path/method parameter that resolved the conflict in the first place), so
    // it's passed in here rather than read off the entity.
    public static IngestionConflictResponse from(IngestionConflict conflict, UUID cohortId) {
        List<ConflictCandidate> candidates = ConflictPayloadCodec.read(conflict.getIncomingPayloadJson());
        return new IngestionConflictResponse(
            conflict.getId(),
            conflict.getIngestionRunId(),
            cohortId,
            conflict.getLearnerId(),
            null,
            conflict.getLabId(),
            null,
            conflict.getConflictKind(),
            conflict.getExistingResultId(),
            null,
            candidates,
            ConflictPayloadCodec.readMap(conflict.getIncomingPayloadJson()),
            ConflictRemediation.describe(candidates),
            conflict.getStatus(),
            conflict.getResolvedBy(),
            conflict.getResolvedAt(),
            conflict.getResolutionNote(),
            conflict.getCreatedAt(),
            conflict.getUpdatedAt()
        );
    }

    /** Adds the reference data the entity only holds ids for. */
    public IngestionConflictResponse withReferenceData(
        String resolvedLearnerName,
        String resolvedLabTitle,
        ExistingResultView resolvedExistingResult,
        List<ConflictCandidate> resolvedCandidates
    ) {
        return new IngestionConflictResponse(
            id, ingestionRunId, cohortId, learnerId, resolvedLearnerName, labId, resolvedLabTitle,
            conflictKind, existingResultId, resolvedExistingResult, resolvedCandidates, incomingPayload,
            remediation, status, resolvedBy, resolvedAt, resolutionNote, createdAt, updatedAt);
    }
}
