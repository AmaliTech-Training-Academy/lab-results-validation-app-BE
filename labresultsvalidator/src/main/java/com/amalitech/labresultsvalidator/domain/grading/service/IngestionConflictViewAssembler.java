package com.amalitech.labresultsvalidator.domain.grading.service;

import com.amalitech.labresultsvalidator.domain.grading.dto.ConflictCandidate;
import com.amalitech.labresultsvalidator.domain.grading.dto.ExistingResultView;
import com.amalitech.labresultsvalidator.domain.grading.dto.IngestionConflictResponse;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionConflict;
import com.amalitech.labresultsvalidator.domain.grading.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.grading.repository.LabResultRepository;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.reference.entity.Lab;
import com.amalitech.labresultsvalidator.domain.reference.entity.Learner;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LearnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fills in the reference data a conflict only stores ids for, so the queue can show the admin what
 * they are actually choosing between (B10 AC1).
 *
 * <p>An {@code IngestionConflict} holds {@code learnerId}, {@code labId} and {@code existingResultId}
 * and nothing else human-readable, and {@code IngestionConflictResponse.from} is static so it cannot
 * look anything up. That left the reviewer picking between two marks with only UUIDs on screen.
 *
 * <p>Every lookup is batched per page via {@code findAllById} rather than issued per row — the queue
 * is paginated and a per-conflict lookup would be four queries a row. Missing references resolve to
 * null rather than failing: a learner or lab can be deleted (the FKs are {@code ON DELETE SET NULL}),
 * and a stale conflict must still be listable so it can be dismissed.
 */
@Component
@RequiredArgsConstructor
public class IngestionConflictViewAssembler {

    private final LearnerRepository learnerRepository;
    private final LabRepository labRepository;
    private final LabResultRepository labResultRepository;
    private final InstructorContactRepository instructorContactRepository;

    public Page<IngestionConflictResponse> assemble(Page<IngestionConflict> conflicts, UUID cohortId) {
        List<IngestionConflictResponse> enriched = assemble(conflicts.getContent(), cohortId);
        Map<UUID, IngestionConflictResponse> byId = enriched.stream()
            .collect(Collectors.toMap(IngestionConflictResponse::id, Function.identity()));
        return conflicts.map(c -> byId.get(c.getId()));
    }

    public IngestionConflictResponse assemble(IngestionConflict conflict, UUID cohortId) {
        return assemble(List.of(conflict), cohortId).get(0);
    }

    public List<IngestionConflictResponse> assemble(List<IngestionConflict> conflicts, UUID cohortId) {
        if (conflicts.isEmpty()) {
            return List.of();
        }

        List<IngestionConflictResponse> base = conflicts.stream()
            .map(c -> IngestionConflictResponse.from(c, cohortId))
            .toList();

        Map<UUID, Learner> learners = byId(learnerRepository.findAllById(
            ids(base, IngestionConflictResponse::learnerId)), Learner::getId);
        Map<UUID, Lab> labs = byId(labRepository.findAllById(
            ids(base, IngestionConflictResponse::labId)), Lab::getId);
        Map<UUID, LabResult> existingResults = byId(labResultRepository.findAllById(
            ids(base, IngestionConflictResponse::existingResultId)), LabResult::getId);

        // Reviewer names come from two places — each candidate row's resolved reviewer, and the
        // reviewer on the already-committed row — so instructors are collected after the lab results
        // are in hand, and fetched in one query for both.
        Set<UUID> instructorIds = new HashSet<>();
        for (IngestionConflictResponse response : base) {
            response.candidates().stream()
                .map(ConflictCandidate::instructorContactId)
                .filter(Objects::nonNull)
                .forEach(instructorIds::add);
        }
        existingResults.values().stream()
            .map(LabResult::getInstructorContactId)
            .filter(Objects::nonNull)
            .forEach(instructorIds::add);
        Map<UUID, InstructorContact> instructors = byId(
            instructorContactRepository.findAllById(instructorIds), InstructorContact::getId);

        List<IngestionConflictResponse> assembled = new ArrayList<>(base.size());
        for (IngestionConflictResponse response : base) {
            Learner learner = response.learnerId() != null ? learners.get(response.learnerId()) : null;
            Lab lab = response.labId() != null ? labs.get(response.labId()) : null;
            LabResult existing = response.existingResultId() != null
                ? existingResults.get(response.existingResultId())
                : null;

            ExistingResultView existingView = existing == null
                ? null
                : ExistingResultView.from(existing)
                    .withReviewerName(reviewerName(instructors, existing.getInstructorContactId()));

            List<ConflictCandidate> candidates = response.candidates().stream()
                .map(c -> c.withReviewerName(reviewerName(instructors, c.instructorContactId())))
                .toList();

            assembled.add(response.withReferenceData(
                learner != null ? learner.getFullName() : null,
                lab != null ? lab.getTitle() : null,
                existingView,
                candidates));
        }
        return assembled;
    }

    private static String reviewerName(Map<UUID, InstructorContact> instructors, UUID instructorContactId) {
        if (instructorContactId == null) {
            return null;
        }
        InstructorContact contact = instructors.get(instructorContactId);
        return contact != null ? contact.getFullName() : null;
    }

    private static Set<UUID> ids(List<IngestionConflictResponse> responses,
                                 Function<IngestionConflictResponse, UUID> extractor) {
        return responses.stream().map(extractor).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static <T> Map<UUID, T> byId(List<T> entities, Function<T, UUID> idExtractor) {
        return entities.stream().collect(Collectors.toMap(idExtractor, Function.identity(), (a, b) -> a));
    }
}
