package com.amalitech.labresultsvalidator.domain.user.event;

public record AdminProvisionedEvent(String email, String temporaryPassword) {
}
