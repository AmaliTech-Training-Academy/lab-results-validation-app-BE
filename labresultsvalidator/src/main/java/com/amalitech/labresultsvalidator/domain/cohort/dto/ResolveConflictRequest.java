package com.amalitech.labresultsvalidator.domain.cohort.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolveConflictRequest {

    @NotNull(message = "action is required")
    private ConflictResolutionAction action;

    private String note;
}
