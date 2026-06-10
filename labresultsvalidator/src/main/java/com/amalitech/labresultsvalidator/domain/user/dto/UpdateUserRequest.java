package com.amalitech.labresultsvalidator.domain.user.dto;

import jakarta.validation.constraints.Email;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateUserRequest {

    @Email(message = "Must be a valid email address")
    private String email;

    private Boolean isActive;
}
