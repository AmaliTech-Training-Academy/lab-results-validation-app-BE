package com.amalitech.labresultsvalidator.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginResponse {

    private final String token;
    private final String email;
    private final String role;
    private final boolean mustChangePassword;

    @JsonIgnore
    private final String refreshToken;
}
