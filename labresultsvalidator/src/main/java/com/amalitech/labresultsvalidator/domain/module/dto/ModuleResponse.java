package com.amalitech.labresultsvalidator.domain.module.dto;

import com.amalitech.labresultsvalidator.domain.enums.ModuleStatus;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class ModuleResponse {

    private UUID id;
    private String name;
    private int sequence;
    private UUID specializationId;
    private String specializationName;
    private UUID cohortId;
    private String cohortName;
    private ModuleStatus status;
}
