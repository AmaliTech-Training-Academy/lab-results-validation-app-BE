package com.amalitech.labresultsvalidator.domain.cohort.dto;

import java.util.UUID;

public record StreamJobHandle(UUID jobId, boolean running) {
}
