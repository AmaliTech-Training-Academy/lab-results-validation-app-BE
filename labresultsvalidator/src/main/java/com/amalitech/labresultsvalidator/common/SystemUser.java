package com.amalitech.labresultsvalidator.common;

import java.util.UUID;

/**
 * Pseudo-actor for system-initiated work (the scheduled sync) that has no human to attribute to.
 * Seeded once in {@code V24__seed_system_user.sql}, inert ({@code is_active=false}, so it can
 * never authenticate) — used only as a {@code users(id)} FK target for {@code triggered_by},
 * {@code created_by}, {@code updated_by} columns (PRD Epic D, D1 AC3).
 */
public final class SystemUser {

    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private SystemUser() {
    }
}
