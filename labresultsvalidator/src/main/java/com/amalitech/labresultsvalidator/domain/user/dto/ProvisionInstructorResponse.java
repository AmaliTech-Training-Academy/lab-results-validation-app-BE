package com.amalitech.labresultsvalidator.domain.user.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class ProvisionInstructorResponse {

    private final UUID id;
    private final String email;
}
