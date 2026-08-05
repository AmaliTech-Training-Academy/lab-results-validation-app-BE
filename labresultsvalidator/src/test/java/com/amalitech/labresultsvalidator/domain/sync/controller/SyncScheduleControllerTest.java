package com.amalitech.labresultsvalidator.domain.sync.controller;

import com.amalitech.labresultsvalidator.common.exceptions.GlobalExceptionHandler;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.sync.dto.SyncScheduleRequest;
import com.amalitech.labresultsvalidator.domain.sync.dto.SyncScheduleResponse;
import com.amalitech.labresultsvalidator.domain.sync.entity.ScheduleFrequency;
import com.amalitech.labresultsvalidator.domain.sync.service.SyncScheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SyncScheduleControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private SyncScheduleService syncScheduleService;

    @InjectMocks
    private SyncScheduleController syncScheduleController;

    private static final String BASE_URL = "/api/v1/sync-schedules";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(syncScheduleController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        SyncScheduleRequest request = SyncScheduleRequest.builder()
            .frequency(ScheduleFrequency.DAILY)
            .timeOfDay(LocalTime.of(8, 0))
            .build();
        SyncScheduleResponse response = new SyncScheduleResponse(
            UUID.randomUUID(), null, null, ScheduleFrequency.DAILY, LocalTime.of(8, 0), null, "GMT", true,
            OffsetDateTime.now(), OffsetDateTime.now());
        when(syncScheduleService.create(any(SyncScheduleRequest.class))).thenReturn(response);

        mockMvc.perform(post(BASE_URL)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.frequency").value("DAILY"))
            .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void create_weeklyWithoutDayOfWeek_returns422() throws Exception {
        SyncScheduleRequest request = SyncScheduleRequest.builder()
            .frequency(ScheduleFrequency.WEEKLY)
            .timeOfDay(LocalTime.of(8, 0))
            .build();
        when(syncScheduleService.create(any(SyncScheduleRequest.class)))
            .thenThrow(new UnprocessableEntityException("dayOfWeek is required when frequency is WEEKLY"));

        mockMvc.perform(post(BASE_URL)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void list_returnsAllSchedules() throws Exception {
        SyncScheduleResponse response = new SyncScheduleResponse(
            UUID.randomUUID(), "Nightly", null, ScheduleFrequency.DAILY, LocalTime.of(2, 0), null, "GMT", true,
            OffsetDateTime.now(), OffsetDateTime.now());
        when(syncScheduleService.list()).thenReturn(List.of(response));

        mockMvc.perform(get(BASE_URL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("Nightly"));
    }

    @Test
    void get_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(syncScheduleService.get(id))
            .thenThrow(new ResourceNotFoundException("Sync schedule not found with ID: " + id));

        mockMvc.perform(get(BASE_URL + "/" + id))
            .andExpect(status().isNotFound());
    }

    @Test
    void update_existingSchedule_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        SyncScheduleRequest request = SyncScheduleRequest.builder()
            .frequency(ScheduleFrequency.WEEKLY)
            .dayOfWeek(DayOfWeek.FRIDAY)
            .timeOfDay(LocalTime.of(17, 0))
            .enabled(false)
            .build();
        SyncScheduleResponse response = new SyncScheduleResponse(
            id, null, null, ScheduleFrequency.WEEKLY, LocalTime.of(17, 0), DayOfWeek.FRIDAY, "GMT", false,
            OffsetDateTime.now(), OffsetDateTime.now());
        when(syncScheduleService.update(eq(id), any(SyncScheduleRequest.class))).thenReturn(response);

        mockMvc.perform(put(BASE_URL + "/" + id)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.dayOfWeek").value("FRIDAY"));
    }

    @Test
    void delete_existingSchedule_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete(BASE_URL + "/" + id))
            .andExpect(status().isNoContent());
    }
}
