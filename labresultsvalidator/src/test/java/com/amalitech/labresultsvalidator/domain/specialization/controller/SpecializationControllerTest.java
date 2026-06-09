package com.amalitech.labresultsvalidator.domain.specialization.controller;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.GlobalExceptionHandler;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.specialization.dto.SpecializationResponse;
import com.amalitech.labresultsvalidator.domain.specialization.service.SpecializationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpecializationControllerTest {

    private MockMvc mockMvc;
    private SpecializationService specializationService;

    private static final String BASE_URL = "/api/v1/admin/specializations";
    private final UUID cohortId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        specializationService = mock(SpecializationService.class);
        SpecializationController controller =
                new SpecializationController(specializationService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createSpecialization_withValidRequest_returns201WithSpecializationData()
            throws Exception {
        SpecializationResponse response = SpecializationResponse.builder()
                .id(UUID.randomUUID())
                .cohortId(cohortId)
                .name("Software Engineering")
                .code("SWE")
                .build();

        when(specializationService.createSpecialization(any()))
                .thenReturn(response);

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cohortId": "%s",
                                  "name": "Software Engineering",
                                  "code": "SWE"
                                }
                                """.formatted(cohortId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("Specialization created successfully"))
                .andExpect(jsonPath("$.data.name").value("Software Engineering"))
                .andExpect(jsonPath("$.data.code").value("SWE"))
                .andExpect(jsonPath("$.data.cohortId").value(cohortId.toString()));
    }

    @Test
    void createSpecialization_withMissingCohortId_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Software Engineering",
                                  "code": "SWE"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createSpecialization_withMissingName_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cohortId": "%s",
                                  "code": "SWE"
                                }
                                """.formatted(cohortId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createSpecialization_withMissingCode_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cohortId": "%s",
                                  "name": "Software Engineering"
                                }
                                """.formatted(cohortId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createSpecialization_withBlankName_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cohortId": "%s",
                                  "name": "",
                                  "code": "SWE"
                                }
                                """.formatted(cohortId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createSpecialization_withBlankCode_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cohortId": "%s",
                                  "name": "Software Engineering",
                                  "code": ""
                                }
                                """.formatted(cohortId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createSpecialization_withCohortNotFound_returns404() throws Exception {
        when(specializationService.createSpecialization(any()))
                .thenThrow(new ResourceNotFoundException(
                        "Cohort with id '" + cohortId + "' not found"));

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cohortId": "%s",
                                  "name": "Software Engineering",
                                  "code": "SWE"
                                }
                                """.formatted(cohortId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("Cohort with id '" + cohortId + "' not found"));
    }

    @Test
    void createSpecialization_withDuplicateName_returns409() throws Exception {
        when(specializationService.createSpecialization(any()))
                .thenThrow(new DuplicateResourceException(
                        "Specialization with name 'Software Engineering' "
                                + "already exists in this cohort"));

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cohortId": "%s",
                                  "name": "Software Engineering",
                                  "code": "SWE"
                                }
                                """.formatted(cohortId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "Specialization with name 'Software Engineering' "
                                + "already exists in this cohort"));
    }

    @Test
    void createSpecialization_withDuplicateCode_returns409() throws Exception {
        when(specializationService.createSpecialization(any()))
                .thenThrow(new DuplicateResourceException(
                        "Specialization with code 'SWE' already exists in this cohort"));

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cohortId": "%s",
                                  "name": "Software Engineering",
                                  "code": "SWE"
                                }
                                """.formatted(cohortId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "Specialization with code 'SWE' already exists in this cohort"));
    }
}