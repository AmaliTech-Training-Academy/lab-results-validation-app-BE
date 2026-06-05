package com.amalitech.labresultsvalidator.domain.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ProvisionInstructorRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    @Pattern(
        regexp = "^[\\w.+\\-]+@amalitech\\.com$",
        message = "Email must be an @amalitech.com address"
    )
    private String email;
}
