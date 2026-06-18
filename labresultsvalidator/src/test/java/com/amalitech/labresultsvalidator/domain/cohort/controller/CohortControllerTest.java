package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.GlobalExceptionHandler;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortResponse;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CohortControllerTest {

    private MockMvc mockMvc;

    @Mock
    private CohortService cohortService;

    @InjectMocks
    private CohortController cohortController;

    private static final String BASE_URL = "/api/v1/admin/cohorts";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(cohortController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createCohort_withValidRequest_returns201WithCohortData() throws Exception {
        CohortResponse cohortResponse = CohortResponse.builder()
                .id(UUID.randomUUID())
                .name("Cohort 12")
                .startDate(LocalDate.of(2025, 1, 1))
                .endDate(LocalDate.of(2025, 6, 30))
                .active(true)
                .build();

        when(cohortService.createCohort(any())).thenReturn(cohortResponse);

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Cohort 12",
                                  "startDate": "2025-01-01",
                                  "endDate": "2025-06-30"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Cohort created successfully"))
                .andExpect(jsonPath("$.data.name").value("Cohort 12"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void createCohort_withMissingName_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startDate": "2025-01-01",
                                  "endDate": "2025-06-30"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createCohort_withMissingStartDate_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Cohort 12",
                                  "endDate": "2025-06-30"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createCohort_withMissingEndDate_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Cohort 12",
                                  "startDate": "2025-01-01"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createCohort_withDuplicateName_returns409() throws Exception {
        when(cohortService.createCohort(any()))
                .thenThrow(new DuplicateResourceException("Cohort with name 'Cohort 12' already exists"));

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Cohort 12",
                                  "startDate": "2025-01-01",
                                  "endDate": "2025-06-30"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Cohort with name 'Cohort 12' already exists"));
    }

    @Test
    void createCohort_withEndDateBeforeStartDate_returns400() throws Exception {
        when(cohortService.createCohort(any()))
                .thenThrow(new IllegalArgumentException("End date must be after start date"));

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Cohort 12",
                                  "startDate": "2025-06-30",
                                  "endDate": "2025-01-01"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("End date must be after start date"));
    }

    @Test
    void createCohort_withBlankName_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "startDate": "2025-01-01",
                                  "endDate": "2025-06-30"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── updateCohortStatus ────────────────────────────────────────────────────

    @Test
    void updateCohortStatus_withActiveTrue_returns200WithActivatedMessage() throws Exception {
        UUID id = UUID.randomUUID();
        CohortResponse cohortResponse = CohortResponse.builder()
                .id(id)
                .name("Cohort 12")
                .startDate(LocalDate.of(2025, 1, 1))
                .endDate(LocalDate.of(2025, 6, 30))
                .active(true)
                .build();

        when(cohortService.updateCohortStatus(any(), any())).thenReturn(cohortResponse);

        mockMvc.perform(patch(BASE_URL + "/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Cohort activated successfully"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void updateCohortStatus_withActiveFalse_returns200WithDeactivatedMessage() throws Exception {
        UUID id = UUID.randomUUID();
        CohortResponse cohortResponse = CohortResponse.builder()
                .id(id)
                .name("Cohort 12")
                .startDate(LocalDate.of(2025, 1, 1))
                .endDate(LocalDate.of(2025, 6, 30))
                .active(false)
                .build();

        when(cohortService.updateCohortStatus(any(), any())).thenReturn(cohortResponse);

        mockMvc.perform(patch(BASE_URL + "/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Cohort deactivated successfully"))
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    void updateCohortStatus_whenNotFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(cohortService.updateCohortStatus(any(), any()))
                .thenThrow(new ResourceNotFoundException("Cohort with id '" + id + "' not found"));

        mockMvc.perform(patch(BASE_URL + "/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void updateCohortStatus_withMissingActive_returns400() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(patch(BASE_URL + "/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}