package com.amalitech.labresultsvalidator.domain.standup.service;

import com.amalitech.labresultsvalidator.domain.auditlog.service.AuditEventService;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.reference.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.standup.entity.CohortStandupPending;
import com.amalitech.labresultsvalidator.domain.standup.gate.ValidatedReferenceBundle;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.standup.repository.CohortStandupPendingRepository;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorSpecializationAssignmentRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.SpecializationRepository;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemDetails;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferenceCommitServiceTest {

    private static final String DRIVE_ID = "drive-1";
    private static final String ITEM_ID = "folder-1";

    @Mock
    private CohortRepository cohortRepository;
    @Mock
    private CohortStandupPendingRepository pendingRepository;
    @Mock
    private SpecializationRepository specializationRepository;
    @Mock
    private LabModuleRepository labModuleRepository;
    @Mock
    private LabRepository labRepository;
    @Mock
    private LearnerRepository learnerRepository;
    @Mock
    private InstructorContactRepository instructorContactRepository;
    @Mock
    private InstructorSpecializationAssignmentRepository instructorSpecializationAssignmentRepository;
    @Mock
    private AuditEventService auditEventService;
    @Mock
    private GraphDriveService graphDriveService;

    private ReferenceCommitService service;

    private UUID cohortId;
    private UUID actorId;
    private Cohort cohort;

    @BeforeEach
    void setUp() throws Exception {
        service = new ReferenceCommitService(
            cohortRepository, pendingRepository, specializationRepository, labModuleRepository,
            labRepository, learnerRepository, instructorContactRepository,
            instructorSpecializationAssignmentRepository, auditEventService, graphDriveService,
            new ObjectMapper());

        cohortId = UUID.randomUUID();
        actorId = UUID.randomUUID();

        cohort = Cohort.builder()
            .id(cohortId)
            .name("Test Cohort")
            .lifecycleState(CohortLifecycleState.DRAFT)
            .sharepointDriveId(DRIVE_ID)
            .sharepointItemId(ITEM_ID)
            .build();

        String bundleJson = new ObjectMapper().writeValueAsString(
            new ValidatedReferenceBundle(List.of(), List.of(), List.of(), List.of(), List.of()));
        CohortStandupPending pending = CohortStandupPending.builder()
            .cohortId(cohortId)
            .bundleJson(bundleJson)
            .passedAt(OffsetDateTime.now())
            .expiresAt(OffsetDateTime.now().plusMinutes(10))
            .build();

        when(pendingRepository.findById(cohortId)).thenReturn(Optional.of(pending));
        when(cohortRepository.findByIdAndIsActiveTrue(cohortId)).thenReturn(Optional.of(cohort));
        when(specializationRepository.findAllByCohortId(cohortId)).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedPayload() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(auditEventService).record(eq("REFERENCE_ACCEPTED"), eq(cohortId), eq(actorId), captor.capture());
        return (Map<String, Object>) captor.getValue();
    }

    @Test
    void acceptAndCommit_capturesTheSharepointVersionTheBundleWasAcceptedFrom() {
        when(graphDriveService.getItem(DRIVE_ID, ITEM_ID))
            .thenReturn(new DriveItemDetails("Reference Data", null, null, "cTag-42", null, "https://sp/folder"));

        service.acceptAndCommit(cohortId, actorId);

        // D2 AC2 — REFERENCE_ACCEPTED must capture the SharePoint version the bundle came from.
        assertThat(capturedPayload()).containsEntry("sharepointVersionId", "cTag-42");
    }

    @Test
    void acceptAndCommit_degradesToEmptyVersionWhenGraphLookupFails() {
        when(graphDriveService.getItem(DRIVE_ID, ITEM_ID))
            .thenThrow(new GraphAccessException("throttled"));

        // A metadata-lookup failure must not block acceptance itself.
        service.acceptAndCommit(cohortId, actorId);

        assertThat(capturedPayload()).containsEntry("sharepointVersionId", "");
    }

    @Test
    void acceptAndCommit_sameInstructorNameDifferentEmailAcrossCohorts_doesNotDuplicateTheContact() {
        // Regression test for the instructor-duplication bug: Cohort A already knows "Eric
        // Boateng" by one email; Cohort B's Instructor Database lists the same name under a
        // different email. The commit must resolve to the SAME InstructorContact (matched by
        // full name) instead of creating a second row — a second row with the same name is
        // exactly what breaks the weekly sync's by-name reviewer resolution.
        String specName = "Software Engineering";
        UUID specId = UUID.randomUUID();

        when(graphDriveService.getItem(DRIVE_ID, ITEM_ID))
            .thenReturn(new DriveItemDetails("Reference Data", null, null, "cTag-1", null, "https://sp/folder"));

        String bundleJson;
        try {
            bundleJson = new ObjectMapper().writeValueAsString(new ValidatedReferenceBundle(
                List.of(new ValidatedReferenceBundle.SpecializationRow("SPEC1", specName)),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ValidatedReferenceBundle.InstructorRow(
                    "Eric Boateng", "eric.new@example.com", specName))));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        CohortStandupPending pending = CohortStandupPending.builder()
            .cohortId(cohortId)
            .bundleJson(bundleJson)
            .passedAt(OffsetDateTime.now())
            .expiresAt(OffsetDateTime.now().plusMinutes(10))
            .build();
        when(pendingRepository.findById(cohortId)).thenReturn(Optional.of(pending));

        when(specializationRepository.save(any(Specialization.class)))
            .thenAnswer(inv -> {
                Specialization spec = inv.getArgument(0);
                spec.setId(specId);
                return spec;
            });

        // Cohort A already created this instructor under a different email — the lookup that
        // now matters is by full name, not by email.
        InstructorContact existing = InstructorContact.builder()
            .id(UUID.randomUUID())
            .email("eric.old@example.com")
            .fullName("Eric Boateng")
            .isActive(true)
            .build();
        when(instructorContactRepository.findByFullNameIgnoreCase("Eric Boateng"))
            .thenReturn(Optional.of(existing));
        when(instructorContactRepository.existsByEmailIgnoreCase("eric.new@example.com")).thenReturn(false);
        when(instructorContactRepository.save(any(InstructorContact.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        service.acceptAndCommit(cohortId, actorId);

        // No new InstructorContact was created for Cohort B's row.
        verify(instructorContactRepository, never()).findByEmailIgnoreCase(any());
        ArgumentCaptor<InstructorContact> savedCaptor = ArgumentCaptor.forClass(InstructorContact.class);
        verify(instructorContactRepository).save(savedCaptor.capture());
        InstructorContact saved = savedCaptor.getValue();
        assertThat(saved.getId()).isEqualTo(existing.getId());
        assertThat(saved.getFullName()).isEqualTo("Eric Boateng");
        assertThat(saved.getEmail()).isEqualTo("eric.new@example.com");
    }
}
