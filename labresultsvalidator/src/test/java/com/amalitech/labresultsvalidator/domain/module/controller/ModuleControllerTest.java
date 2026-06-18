package com.amalitech.labresultsvalidator.domain.module.controller;

import com.amalitech.labresultsvalidator.common.exceptions.GlobalExceptionHandler;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.enums.ModuleStatus;
import com.amalitech.labresultsvalidator.domain.module.dto.CreateModuleRequest;
import com.amalitech.labresultsvalidator.domain.module.dto.ModuleResponse;
import com.amalitech.labresultsvalidator.domain.module.dto.PatchModuleRequest;
import com.amalitech.labresultsvalidator.domain.module.service.ModuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ModuleControllerTest {

    @Mock
    private ModuleService moduleService;

    @InjectMocks
    private ModuleController moduleController;

    private MockMvc mockMvc;

    private static final UUID COHORT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SPEC_ID   = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MODULE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(moduleController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    private ModuleResponse buildActiveResponse() {
        return ModuleResponse.builder()
                .id(MODULE_ID)
                .name("Intro to Python")
                .sequence(1)
                .specializationId(SPEC_ID)
                .specializationName("Data Science")
                .cohortId(COHORT_ID)
                .cohortName("Cohort 7")
                .status(ModuleStatus.ACTIVE)
                .build();
    }

    // ───────────────────────── POST /api/v1/modules ──────────────────────────

    @Test
    void createModule_withValidRequest_returns201AndModuleData() throws Exception {
        when(moduleService.createModule(any(CreateModuleRequest.class)))
                .thenReturn(buildActiveResponse());

        String body = """
                {
                  "name": "Intro to Python",
                  "cohortId": "%s",
                  "specializationId": "%s"
                }
                """.formatted(COHORT_ID, SPEC_ID);

        mockMvc.perform(post("/api/v1/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Module created successfully"))
                .andExpect(jsonPath("$.data.name").value("Intro to Python"))
                .andExpect(jsonPath("$.data.sequence").value(1))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void createModule_withBlankName_returns400() throws Exception {
        String body = """
                {
                  "name": "",
                  "cohortId": "%s",
                  "specializationId": "%s"
                }
                """.formatted(COHORT_ID, SPEC_ID);

        mockMvc.perform(post("/api/v1/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createModule_withMissingCohortId_returns400() throws Exception {
        String body = """
                {
                  "name": "Intro to Python",
                  "specializationId": "%s"
                }
                """.formatted(SPEC_ID);

        mockMvc.perform(post("/api/v1/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createModule_withMissingSpecializationId_returns400() throws Exception {
        String body = """
                {
                  "name": "Intro to Python",
                  "cohortId": "%s"
                }
                """.formatted(COHORT_ID);

        mockMvc.perform(post("/api/v1/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createModule_whenCohortSpecCombinationInvalid_returns422() throws Exception {
        when(moduleService.createModule(any(CreateModuleRequest.class)))
                .thenThrow(new UnprocessableEntityException(
                        "Cohort and specialization combination does not exist"));

        String body = """
                {
                  "name": "Intro to Python",
                  "cohortId": "%s",
                  "specializationId": "%s"
                }
                """.formatted(COHORT_ID, SPEC_ID);

        mockMvc.perform(post("/api/v1/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("Cohort and specialization combination does not exist"));
    }

    // ───────────────────── GET /api/v1/modules ────────────────────────────────

    @Test
    void getModules_withNoQueryParams_returns200WithList() throws Exception {
        when(moduleService.getModules(isNull(), isNull(), any()))
                .thenReturn(PagedResponse.of(new PageImpl<>(List.of(buildActiveResponse()))));

        mockMvc.perform(get("/api/v1/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content[0].name").value("Intro to Python"));
    }

    @Test
    void getModules_withCohortAndSpecFilter_returns200WithFilteredList() throws Exception {
        when(moduleService.getModules(eq(COHORT_ID), eq(SPEC_ID), any()))
                .thenReturn(PagedResponse.of(new PageImpl<>(List.of(buildActiveResponse()))));

        mockMvc.perform(get("/api/v1/modules")
                        .param("cohortId", COHORT_ID.toString())
                        .param("specializationId", SPEC_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    void getModules_withOnlyCohortId_returns200() throws Exception {
        when(moduleService.getModules(eq(COHORT_ID), isNull(), any()))
                .thenReturn(PagedResponse.of(new PageImpl<>(List.of(buildActiveResponse()))));

        mockMvc.perform(get("/api/v1/modules")
                        .param("cohortId", COHORT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    // ───────────────────── PATCH /api/v1/modules/{id} ────────────────────────

    @Test
    void patchModule_withArchivedStatus_returns200AndArchivedModule() throws Exception {
        ModuleResponse archived = ModuleResponse.builder()
                .id(MODULE_ID)
                .name("Intro to Python")
                .sequence(1)
                .specializationId(SPEC_ID)
                .specializationName("Data Science")
                .cohortId(COHORT_ID)
                .cohortName("Cohort 7")
                .status(ModuleStatus.ARCHIVED)
                .build();

        when(moduleService.patchModule(eq(MODULE_ID), any(PatchModuleRequest.class)))
                .thenReturn(archived);

        mockMvc.perform(patch("/api/v1/modules/{id}", MODULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "ARCHIVED" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));
    }

    @Test
    void patchModule_withNonExistentId_returns404() throws Exception {
        when(moduleService.patchModule(eq(MODULE_ID), any(PatchModuleRequest.class)))
                .thenThrow(new ResourceNotFoundException("Module not found with ID: " + MODULE_ID));

        mockMvc.perform(patch("/api/v1/modules/{id}", MODULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "ARCHIVED" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

}
