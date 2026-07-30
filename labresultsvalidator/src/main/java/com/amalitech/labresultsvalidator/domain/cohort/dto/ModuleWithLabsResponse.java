package com.amalitech.labresultsvalidator.domain.cohort.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModuleWithLabsResponse {
    private UUID id;
    private UUID specializationId;
    private String name;
    private String code;
    private String status;
    private List<LabResponse> labs;
}
