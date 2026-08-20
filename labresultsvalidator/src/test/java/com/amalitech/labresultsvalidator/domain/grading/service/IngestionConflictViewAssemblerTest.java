package com.amalitech.labresultsvalidator.domain.grading.service;

import com.amalitech.labresultsvalidator.domain.grading.dto.ConflictCandidate;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionConflictViewAssemblerTest {

    @Mock
    private LearnerRepository learnerRepository;
    @Mock
    private LabRepository labRepository;
    @Mock
    private LabResultRepository labResultRepository;
    @Mock
    private InstructorContactRepository instructorContactRepository;

    private IngestionConflictViewAssembler assembler;

    private UUID cohortId;
    private UUID learnerId;
    private UUID labId;
    private UUID existingResultId;
    private UUID reviewerId;

    @BeforeEach
    void setUp() {
        assembler = new IngestionConflictViewAssembler(
            learnerRepository, labRepository, labResultRepository, instructorContactRepository);
        cohortId = UUID.randomUUID();
        learnerId = UUID.randomUUID();
        labId = UUID.randomUUID();
        existingResultId = UUID.randomUUID();
        reviewerId = UUID.randomUUID();
        lenient().when(learnerRepository.findAllById(any())).thenReturn(List.of(
            Learner.builder().id(learnerId).fullName("Ama Owusu").build()));
        lenient().when(labRepository.findAllById(any())).thenReturn(List.of(
            Lab.builder().id(labId).title("Lab 3 — Pointers").build()));
        lenient().when(labResultRepository.findAllById(any())).thenReturn(List.of(
            LabResult.builder().id(existingResultId).score(new BigDecimal("88.00"))
                .submittedOn(LocalDate.of(2026, 1, 15)).instructorContactId(reviewerId).build()));
        lenient().when(instructorContactRepository.findAllById(any())).thenReturn(List.of(
            InstructorContact.builder().id(reviewerId).fullName("Kwame Mensah").build()));
    }

    private IngestionConflict duplicateConflict() {
        return IngestionConflict.builder()
            .id(UUID.randomUUID())
            .ingestionRunId(UUID.randomUUID())
            .learnerId(learnerId)
            .labId(labId)
            .existingResultId(existingResultId)
            .status("PENDING")
            .incomingPayloadJson("{\"candidates\":["
                + "{\"fileName\":\"Module 1 Grading.xlsx\",\"sheetName\":\"Module-1\",\"rowNum\":5,"
                + "\"nspName\":\"ama owusu\",\"submittedOn\":\"2026-01-15\",\"score\":\"88.00\","
                + "\"instructorContactId\":\"" + reviewerId + "\"},"
                + "{\"fileName\":\"Module 1 Grading.xlsx\",\"sheetName\":\"Module-1\",\"rowNum\":15,"
                + "\"nspName\":\"ama owusu\",\"submittedOn\":\"2026-01-15\",\"score\":\"98.00\","
                + "\"instructorContactId\":null}]}")
            .build();
    }

    @Test
    void assemble_putsEveryNumberTheAdminIsChoosingBetweenOnTheResponse() {
        IngestionConflictResponse response = assembler.assemble(duplicateConflict(), cohortId);

        // The whole defect: a reviewer picked between 88 and 98 with neither number on screen, only
        // UUIDs for learner, lab and existing result.
        assertThat(response.learnerName()).isEqualTo("Ama Owusu");
        assertThat(response.labTitle()).isEqualTo("Lab 3 — Pointers");
        assertThat(response.existingResult()).isNotNull();
        assertThat(response.existingResult().score()).isEqualByComparingTo("88.00");
        assertThat(response.existingResult().reviewerName()).isEqualTo("Kwame Mensah");

        assertThat(response.candidates()).hasSize(2);
        assertThat(response.candidates()).extracting(c -> c.score().toPlainString())
            .containsExactly("88.00", "98.00");
        assertThat(response.candidates()).extracting(ConflictCandidate::rowNum).containsExactly(5, 15);
        assertThat(response.candidates()).extracting(ConflictCandidate::index).containsExactly(0, 1);
        assertThat(response.candidates().get(0).reviewerName()).isEqualTo("Kwame Mensah");
        assertThat(response.candidates().get(1).reviewerName()).isNull();
    }

    @Test
    void assemble_tellsTheAdminWhereTheDuplicateIsAndThatTheFixIsInTheWorkbook() {
        IngestionConflictResponse response = assembler.assemble(duplicateConflict(), cohortId);

        assertThat(response.remediation())
            .contains("Module 1 Grading.xlsx")
            .contains("sheet Module-1")
            .contains("rows 5 and 15")
            .contains("Remove the extra row there");
    }

    @Test
    void assemble_keepsTheRawStoredPayloadForTheDetailsToggle() {
        IngestionConflictResponse response = assembler.assemble(duplicateConflict(), cohortId);

        assertThat(response.incomingPayload()).containsKey("candidates");
    }

    @Test
    void assemble_missingReferenceData_stillProducesAListableConflict() {
        // Learner/lab FKs are ON DELETE SET NULL, so a stale conflict must remain listable (and
        // dismissable) rather than failing the whole queue page.
        when(learnerRepository.findAllById(any())).thenReturn(List.of());
        when(labRepository.findAllById(any())).thenReturn(List.of());
        when(labResultRepository.findAllById(any())).thenReturn(List.of());

        IngestionConflictResponse response = assembler.assemble(duplicateConflict(), cohortId);

        assertThat(response.learnerName()).isNull();
        assertThat(response.labTitle()).isNull();
        assertThat(response.existingResult()).isNull();
        assertThat(response.candidates()).hasSize(2);
    }

    @Test
    void assemble_emptyList_doesNotQueryAnything() {
        assertThat(assembler.assemble(List.of(), cohortId)).isEmpty();
    }
}
