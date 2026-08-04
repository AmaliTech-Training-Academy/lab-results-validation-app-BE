package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandupPending;
import com.amalitech.labresultsvalidator.domain.cohort.gate.ValidatedReferenceBundle;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortStandupPendingRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.InstructorSpecializationAssignmentRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.SpecializationRepository;
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
import static org.mockito.ArgumentMatchers.eq;
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
}
