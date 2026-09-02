package com.amalitech.labresultsvalidator.domain.notification;

import java.util.Set;

/**
 * The {@code notifications.type} vocabulary, as constrained by {@code chk_notif_type}. Anything not
 * listed here needs a migration before it can be persisted.
 *
 * <p>Constants rather than an enum so existing string-based staging code keeps compiling unchanged.
 */
public final class NotificationTypes {

    /** C3 — one per instructor per run. */
    public static final String INSTRUCTOR_DIGEST = "instructor_digest";

    /** C4 — one per active admin per run. */
    public static final String ADMIN_RUN_DIGEST = "admin_run_digest";

    /** C5 AC1 — a stand-up gate failure. */
    public static final String STANDUP_FAILURE = "standup_failure";

    /** C5 AC1 — a sheet where more than half the graded rows were rejected. */
    public static final String HIGH_FAILURE = "high_failure";

    /** C5 AC1 — duplicates awaiting resolution. */
    public static final String CONFLICT_ALERT = "conflict_alert";

    /**
     * A file the sync run could not even read (bad metadata, download/parse failure, or a failed
     * archive). Unlike {@link #HIGH_FAILURE}, this never produces an {@code IngestionRun}, so
     * without this type the failure had no admin-facing notification at all — only the sync SSE
     * stream and the server log.
     */
    public static final String FILE_READ_FAILURE = "file_read_failure";

    /** C5 AC2 — a cohort reached STOOD_UP. In-app only. */
    public static final String STOOD_UP = "stood_up";

    /**
     * Types raised in-app but deliberately never emailed. C5 AC2 asks for a stood-up confirmation
     * "auto, no email" — every other type is an actionable problem and does get mailed.
     */
    private static final Set<String> IN_APP_ONLY = Set.of(STOOD_UP);

    private NotificationTypes() {
    }

    /**
     * Whether dispatching this type should send an email, or only leave the in-app record.
     *
     * <p>An unrecognised type is treated as emailed: the CHECK constraint makes it near-impossible,
     * and defaulting to sending is safer than silently suppressing an alert.
     */
    public static boolean isEmailed(String type) {
        return !IN_APP_ONLY.contains(type);
    }
}
