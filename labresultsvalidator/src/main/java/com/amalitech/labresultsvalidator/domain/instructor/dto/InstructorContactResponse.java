package com.amalitech.labresultsvalidator.domain.instructor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstructorContactResponse {
    private UUID id;
    private String instructorId;
    private String email;
    private String fullName;
    private boolean isActive;
}
