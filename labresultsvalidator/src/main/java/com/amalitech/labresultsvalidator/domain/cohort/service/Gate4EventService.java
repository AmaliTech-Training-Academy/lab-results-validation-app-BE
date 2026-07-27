package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.domain.cohort.dto.StandupGateEvent;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortGate4JobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class Gate4EventService {

    private static final Logger LOG = LoggerFactory.getLogger(Gate4EventService.class);
    private static final TypeReference<List<Map<String, Object>>> EVENT_LIST_TYPE = new TypeReference<>() {};

    private final CohortGate4JobRepository jobRepository;
    private final StandupSseRegistry sseRegistry;
    private final ObjectMapper objectMapper;

    public Gate4EventService(
        CohortGate4JobRepository jobRepository,
        StandupSseRegistry sseRegistry,
        ObjectMapper objectMapper
    ) {
        this.jobRepository = jobRepository;
        this.sseRegistry = sseRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void emit(UUID jobId, String eventName, Map<String, Object> payload) {
        jobRepository.findById(jobId).ifPresent(job -> {
            List<Map<String, Object>> events = parseEvents(job.getGateEventsJson());
            int index = events.size();

            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("index", index);
            stored.put("event", eventName);
            stored.putAll(payload);
            events.add(stored);

            job.setGateEventsJson(serialize(events));
            jobRepository.save(job);

            sseRegistry.send(jobId, new StandupGateEvent(index, eventName, payload));
            LOG.debug("[gate4-event] job={} index={} event={}", jobId, index, eventName);
        });
    }

    public List<StandupGateEvent> getEvents(UUID jobId) {
        return jobRepository.findById(jobId)
            .map(job -> toGateEvents(parseEvents(job.getGateEventsJson())))
            .orElse(List.of());
    }

    private List<Map<String, Object>> parseEvents(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, EVENT_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            LOG.warn("Failed to parse gate4 events JSON: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    private String serialize(List<Map<String, Object>> events) {
        try {
            return objectMapper.writeValueAsString(events);
        } catch (JsonProcessingException ex) {
            LOG.warn("Failed to serialize gate4 events: {}", ex.getMessage());
            return "[]";
        }
    }

    private List<StandupGateEvent> toGateEvents(List<Map<String, Object>> raw) {
        List<StandupGateEvent> result = new ArrayList<>();
        for (Map<String, Object> entry : raw) {
            int index = entry.containsKey("index") ? ((Number) entry.get("index")).intValue() : result.size();
            String event = (String) entry.getOrDefault("event", "unknown");
            Map<String, Object> payload = new LinkedHashMap<>(entry);
            payload.remove("index");
            payload.remove("event");
            result.add(new StandupGateEvent(index, event, payload));
        }
        return result;
    }
}
