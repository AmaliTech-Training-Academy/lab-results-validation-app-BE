package com.amalitech.labresultsvalidator.domain.standup.service;

import com.amalitech.labresultsvalidator.domain.standup.dto.StandupGateEvent;
import com.amalitech.labresultsvalidator.domain.standup.repository.CohortStandUpJobRepository;
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
public class StandupEventService {

    private static final Logger LOG = LoggerFactory.getLogger(StandupEventService.class);
    private static final TypeReference<List<Map<String, Object>>> EVENT_LIST_TYPE = new TypeReference<>() {};

    private final CohortStandUpJobRepository jobRepository;
    private final StandupSseRegistry sseRegistry;
    private final ObjectMapper objectMapper;

    public StandupEventService(
        CohortStandUpJobRepository jobRepository,
        StandupSseRegistry sseRegistry,
        ObjectMapper objectMapper
    ) {
        this.jobRepository = jobRepository;
        this.sseRegistry = sseRegistry;
        this.objectMapper = objectMapper;
    }

    // REQUIRES_NEW so each event is committed immediately regardless of the caller's transaction,
    // making it available for SSE replay before the pipeline transaction completes.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void emit(UUID jobId, String eventName, Map<String, Object> payload) {
        jobRepository.findById(jobId).ifPresent(job -> {
            // FND-58: shares sseRegistry.lockFor(jobId) with SseGateEventStreamer.stream() so a fresh
            // client's "read stored events, then register" can never straddle this "append, then push
            // live" — see the lock's own doc comment for why that gap used to lose events.
            synchronized (sseRegistry.lockFor(jobId)) {
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
                LOG.debug("[standup-event] job={} index={} event={}", jobId, index, eventName);
            }
        });
    }

    public List<StandupGateEvent> getEvents(UUID jobId) {
        return jobRepository.findById(jobId)
            .map(job -> toGateEvents(parseEvents(job.getGateEventsJson())))
            .orElse(List.of());
    }

    private List<Map<String, Object>> parseEvents(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, EVENT_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            LOG.warn("Failed to parse gate events JSON: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    private String serialize(List<Map<String, Object>> events) {
        try {
            return objectMapper.writeValueAsString(events);
        } catch (JsonProcessingException ex) {
            LOG.warn("Failed to serialize gate events: {}", ex.getMessage());
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
