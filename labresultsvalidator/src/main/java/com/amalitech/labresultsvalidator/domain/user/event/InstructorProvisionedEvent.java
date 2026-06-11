package com.amalitech.labresultsvalidator.domain.user.event;

public record InstructorProvisionedEvent(String email, String temporaryPassword) {
}
