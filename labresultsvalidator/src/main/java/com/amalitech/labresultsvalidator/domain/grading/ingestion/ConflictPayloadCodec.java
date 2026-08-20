package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.domain.grading.dto.ConflictCandidate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes {@code ingestion_conflicts.incoming_payload_json} (B10).
 *
 * <p>An in-file duplicate is <strong>one</strong> conflict holding every conflicting copy of the row,
 * so the payload is an envelope: <code>{"candidates":[{...},{...}]}</code>. Before this fix each copy
 * was stored as its own conflict with a bare single-row object as its payload — resolution then asked
 * the same question once per copy and let contradictory answers through. Rows written back then are
 * still readable: {@link #read} treats a payload with no {@code candidates} key as a single-candidate
 * list, so the pre-existing queue keeps working without a data migration.
 *
 * <p>Static with its own {@code ObjectMapper}, matching the existing precedent in
 * {@code IngestionConflictResponse}. Reading is lenient — a candidate whose {@code score} or
 * {@code submittedOn} is missing or unparseable comes back with those fields null rather than
 * throwing, so one corrupt row cannot take down a whole conflict-queue page. Callers that want to
 * commit a candidate check {@link ConflictCandidate#isCommittable()} first.
 */
public final class ConflictPayloadCodec {

    private static final Logger LOG = LoggerFactory.getLogger(ConflictPayloadCodec.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CANDIDATES = "candidates";

    private ConflictPayloadCodec() {
    }

    /** Serializes every conflicting copy of one duplicated row into the candidates envelope. */
    public static String write(List<ValidatedScoreRow> rows) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (ValidatedScoreRow row : rows) {
            candidates.add(toMap(row));
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(CANDIDATES, candidates);
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            LOG.warn("[conflict] could not serialize {} candidate(s): {}", rows.size(), ex.getMessage());
            return "{\"error\":\"serialization failed\"}";
        }
    }

    /**
     * Parses a stored payload into its candidates, 0-indexed in stored order. Returns an empty list
     * for a null/blank/unparseable payload, or for the {@code {"error":"serialization failed"}}
     * sentinel {@link #write} falls back to — none of those carry a row that could be committed.
     */
    public static List<ConflictCandidate> read(String payloadJson) {
        Map<String, Object> payload = readMap(payloadJson);
        if (payload.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> raw = new ArrayList<>();
        Object candidates = payload.get(CANDIDATES);
        if (candidates instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    raw.add(castMap(map));
                }
            }
        } else if (!payload.containsKey("error")) {
            // Legacy shape: the payload *is* the single held row (one conflict per copy).
            raw.add(payload);
        }

        List<ConflictCandidate> parsed = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            parsed.add(toCandidate(i, raw.get(i)));
        }
        return List.copyOf(parsed);
    }

    /** Parses a stored payload into its raw map form, for the response's verbatim passthrough. */
    public static Map<String, Object> readMap(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(payloadJson, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException ex) {
            LOG.warn("[conflict] could not parse stored incomingPayloadJson: {}", ex.getMessage());
            return Map.of();
        }
    }

    private static Map<String, Object> toMap(ValidatedScoreRow row) {
        // LinkedHashMap, not Map.of — instructorContactId is routinely null (an unresolved reviewer is
        // non-blocking, B6 AC4) and Map.of rejects null values. Key order and value types match the
        // pre-fix payload, so a candidate written now reads back the same as one written before.
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("fileName", row.fileName());
        candidate.put("sheetName", row.sheetName());
        candidate.put("rowNum", row.rowNum());
        candidate.put("nspName", row.nspName());
        candidate.put("submittedOn", row.submittedOn().toString());
        candidate.put("score", row.score().toPlainString());
        UUID instructorContactId = row.instructorContactId();
        candidate.put("instructorContactId", instructorContactId != null ? instructorContactId.toString() : null);
        return candidate;
    }

    private static ConflictCandidate toCandidate(int index, Map<String, Object> candidate) {
        Object rawRowNum = candidate.get("rowNum");
        Object rawScore = candidate.get("score");
        Object rawSubmittedOn = candidate.get("submittedOn");
        Object rawInstructorContactId = candidate.get("instructorContactId");

        Integer rowNum = asInteger(rawRowNum);
        BigDecimal score = asScore(rawScore);
        LocalDate submittedOn = asDate(rawSubmittedOn);
        UUID instructorContactId = asUuid(rawInstructorContactId);

        // A field that was stored but cannot be read is corruption, not absence. Committing such a row
        // as though the value had simply been empty would quietly rewrite a grade from a payload we
        // could not fully understand, so the row is marked unusable and resolution refuses it.
        boolean intact = lostNothing(rawRowNum, rowNum)
            && lostNothing(rawScore, score)
            && lostNothing(rawSubmittedOn, submittedOn)
            && lostNothing(rawInstructorContactId, instructorContactId);

        return new ConflictCandidate(index, asString(candidate.get("fileName")),
            asString(candidate.get("sheetName")), rowNum, asString(candidate.get("nspName")),
            score, submittedOn, instructorContactId, null, intact);
    }

    /** False when a value was present in the payload but could not be parsed. */
    private static boolean lostNothing(Object raw, Object parsed) {
        return raw == null || parsed != null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String asString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value != null ? Integer.valueOf(String.valueOf(value)) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Stored as a plain string by {@link #write}, but tolerates a JSON number too. */
    private static BigDecimal asScore(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return value != null ? new BigDecimal(String.valueOf(value)) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate asDate(Object value) {
        try {
            return value != null ? LocalDate.parse(String.valueOf(value)) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static UUID asUuid(Object value) {
        try {
            return value != null ? UUID.fromString(String.valueOf(value)) : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
