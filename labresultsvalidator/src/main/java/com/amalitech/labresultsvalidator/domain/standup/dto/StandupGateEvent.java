package com.amalitech.labresultsvalidator.domain.standup.dto;

import java.util.Map;

public record StandupGateEvent(int index, String event, Map<String, Object> payload) {}
