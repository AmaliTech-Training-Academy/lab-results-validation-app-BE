package com.amalitech.labresultsvalidator.domain.user.dto;

import com.amalitech.labresultsvalidator.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "The authenticated user's profile")
@Getter
@Builder
public class UserProfileResponse {

    private final UUID id;
    private final String email;
    private final String role;
    private final boolean active;
    private final boolean mustChangePassword;
    private final OffsetDateTime createdAt;

    public static UserProfileResponse from(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole().name())
                .active(user.isActive())
                .mustChangePassword(user.isMustChangePassword())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
