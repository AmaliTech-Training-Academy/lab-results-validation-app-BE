package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.GlobalExceptionHandler;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortReferenceResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.InstructorContactResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.LearnerResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.StandUpJobResponse;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandUpJobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortStandUpJobRepository;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortGate4Service;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortReferenceQueryService;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortService;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortStandUpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CohortControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private CohortService cohortService;

    @Mock
    private CohortStandUpService cohortStandUpService;

    @Mock
    private CohortStandUpJobRepository standUpJobRepository;

    @Mock
    private CohortGate4Service cohortGate4Service;

    @Mock
    private CohortReferenceQueryService cohortReferenceQueryService;

    @InjectMocks
    private CohortController cohortController;

    private static final String BASE_URL = "/api/v1/cohorts";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(cohortController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    }

    private CohortResponse sampleCohort(UUID id) {
        return CohortResponse.builder()
            .id(id)
            .name("Cohort 2026")
            .startDate(LocalDate.of(2026, 1, 1))
            .endDate(LocalDate.of(2026, 12, 31))
            .lifecycleState(CohortLifecycleState.DRAFT)
            .isActive(true)
            .createdAt(OffsetDateTime.now())
            .build();
    }

    @Test
    void createCohort_withValidRequest_returns201() throws Exception {
        UUID id = UUID.randomUUID();
        when(cohortService.createCohort(any())).thenReturn(sampleCohort(id));

        mockMvc.perform(post(BASE_URL)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "Cohort 2026",
                    "startDate", "2026-01-01",
                    "endDate", "2026-12-31"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.lifecycleState").value("DRAFT"));
    }

    @Test
    void createCohort_withBlankName_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "",
                    "startDate", "2026-01-01",
                    "endDate", "2026-12-31"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createCohort_withDuplicateName_returns409() throws Exception {
        when(cohortService.createCohort(any()))
            .thenThrow(new DuplicateResourceException("Cohort name must be unique"));

        mockMvc.perform(post(BASE_URL)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "Cohort 2026",
                    "startDate", "2026-01-01",
                    "endDate", "2026-12-31"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Cohort name must be unique"));
    }

    @Test
    void createCohort_withEndDateBeforeStartDate_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "Cohort 2026",
                    "startDate", "2026-12-31",
                    "endDate", "2026-01-01"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getCohorts_returns200() throws Exception {
        mockMvc.perform(get(BASE_URL))
            .andExpect(status().isOk());
    }

    @Test
    void attachSharePointLink_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        CohortResponse withLink = CohortResponse.builder()
            .id(id)
            .name("Cohort 2026")
            .startDate(LocalDate.of(2026, 1, 1))
            .endDate(LocalDate.of(2026, 12, 31))
            .lifecycleState(CohortLifecycleState.DRAFT)
            .isActive(true)
            .sharepointFolderUrl("https://amalitech.sharepoint.com/sites/labgate/cohort-2026")
            .createdAt(OffsetDateTime.now())
            .build();
        when(cohortService.attachSharePointLink(any(), any())).thenReturn(withLink);

        mockMvc.perform(patch(BASE_URL + "/" + id + "/sharepoint-link")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("folderUrl", "https://amalitech.sharepoint.com/sites/labgate/cohort-2026"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sharepointFolderUrl").value("https://amalitech.sharepoint.com/sites/labgate/cohort-2026"));
    }

    @Test
    void attachSharePointLink_cohortNotFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(cohortService.attachSharePointLink(any(), any()))
            .thenThrow(new ResourceNotFoundException("Cohort not found with ID: " + id));

        mockMvc.perform(patch(BASE_URL + "/" + id + "/sharepoint-link")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("folderUrl", "https://amalitech.sharepoint.com/sites/labgate/cohort-2026"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void attachSharePointLink_cohortNotDraft_returns422() throws Exception {
        UUID id = UUID.randomUUID();
        when(cohortService.attachSharePointLink(any(), any()))
            .thenThrow(new UnprocessableEntityException("A SharePoint link can only be attached to a cohort in DRAFT"));

        mockMvc.perform(patch(BASE_URL + "/" + id + "/sharepoint-link")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("folderUrl", "https://amalitech.sharepoint.com/sites/labgate/cohort-2026"))))
            .andExpect(status().is(422));
    }

    @Test
    void startStandUp_returns202() throws Exception {
        UUID id = UUID.randomUUID();
        when(cohortStandUpService.startStandUp(id)).thenReturn(StandUpJobResponse.builder()
            .id(UUID.randomUUID())
            .cohortId(id)
            .status(CohortStandUpJobStatus.RUNNING)
            .startedAt(OffsetDateTime.now())
            .build());

        mockMvc.perform(post(BASE_URL + "/" + id + "/standup"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    void startStandUp_alreadyRunning_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(cohortStandUpService.startStandUp(id))
            .thenThrow(new DuplicateResourceException("A stand-up job is already running for this cohort"));

        mockMvc.perform(post(BASE_URL + "/" + id + "/standup"))
            .andExpect(status().isConflict());
    }

    @Test
    void getCohortReference_returns200WithNestedBundle() throws Exception {
        UUID id = UUID.randomUUID();
        CohortReferenceResponse reference = CohortReferenceResponse.builder()
            .specializations(List.of())
            .learners(List.of(LearnerResponse.builder()
                .id(UUID.randomUUID())
                .learnerId("DEG-2026-001")
                .fullName("Ama Owusu")
                .email("ama.owusu@example.com")
                .cohortId(id)
                .status("active")
                .build()))
            .instructors(List.of(InstructorContactResponse.builder()
                .id(UUID.randomUUID())
                .instructorId("INS-001")
                .email("kofi.instructor@example.com")
                .fullName("Kofi Mensah")
                .isActive(true)
                .build()))
            .build();
        when(cohortReferenceQueryService.getCohortReference(id)).thenReturn(reference);

        mockMvc.perform(get(BASE_URL + "/" + id + "/reference"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.learners[0].learnerId").value("DEG-2026-001"))
            .andExpect(jsonPath("$.data.instructors[0].instructorId").value("INS-001"));
    }

    @Test
    void getCohortReference_cohortNotFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(cohortReferenceQueryService.getCohortReference(id))
            .thenThrow(new ResourceNotFoundException("Cohort not found with id: " + id));

        mockMvc.perform(get(BASE_URL + "/" + id + "/reference"))
            .andExpect(status().isNotFound());
    }

    @Test
    void lockCohort_stoodUpCohort_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        CohortResponse locked = CohortResponse.builder()
            .id(id)
            .name("Cohort 2026")
            .lifecycleState(CohortLifecycleState.STOOD_UP)
            .isLocked(true)
            .isActive(true)
            .createdAt(OffsetDateTime.now())
            .build();
        when(cohortService.lockCohort(id)).thenReturn(locked);

        mockMvc.perform(patch(BASE_URL + "/" + id + "/lock"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.locked").value(true));
    }

    @Test
    void lockCohort_notStoodUp_returns422() throws Exception {
        UUID id = UUID.randomUUID();
        when(cohortService.lockCohort(id))
            .thenThrow(new UnprocessableEntityException("Only a STOOD_UP cohort can be locked."));

        mockMvc.perform(patch(BASE_URL + "/" + id + "/lock"))
            .andExpect(status().is(422));
    }

    @Test
    void lockCohort_cohortNotFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(cohortService.lockCohort(id))
            .thenThrow(new ResourceNotFoundException("Cohort not found with ID: " + id));

        mockMvc.perform(patch(BASE_URL + "/" + id + "/lock"))
            .andExpect(status().isNotFound());
    }

    @Test
    void unlockCohort_lockedCohort_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        CohortResponse unlocked = CohortResponse.builder()
            .id(id)
            .name("Cohort 2026")
            .lifecycleState(CohortLifecycleState.STOOD_UP)
            .isLocked(false)
            .isActive(true)
            .createdAt(OffsetDateTime.now())
            .build();
        when(cohortService.unlockCohort(id)).thenReturn(unlocked);

        mockMvc.perform(patch(BASE_URL + "/" + id + "/unlock"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.locked").value(false));
    }

    @Test
    void unlockCohort_notLocked_returns422() throws Exception {
        UUID id = UUID.randomUUID();
        when(cohortService.unlockCohort(id))
            .thenThrow(new UnprocessableEntityException("Cohort is not locked."));

        mockMvc.perform(patch(BASE_URL + "/" + id + "/unlock"))
            .andExpect(status().is(422));
    }
}
