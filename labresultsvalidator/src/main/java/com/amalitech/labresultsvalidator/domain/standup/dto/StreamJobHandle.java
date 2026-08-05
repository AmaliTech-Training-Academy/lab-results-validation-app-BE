package com.amalitech.labresultsvalidator.domain.standup.dto;

import java.util.UUID;

public record StreamJobHandle(UUID jobId, boolean running) {
}
