package com.amalitech.labresultsvalidator.infrastructure.graph;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SharePoint/Graph's cTag ("content tag", see {@link DriveItemDetails#versionId}) is an opaque
 * comparison token — its shape is not a documented Microsoft contract, and the rest of this app
 * only ever compares two cTags for equality to detect a content change. In practice it's formatted
 * {@code c:{GUID},N} (optionally HTTP-ETag-quoted, e.g. {@code "c:{GUID},N"}), where N increases on
 * a real content edit. This pulls that number out purely for a friendlier UI label ("v89" instead of
 * the full GUID soup) — never for change-detection logic, which must keep comparing the raw cTag.
 */
public final class SharePointCTag {

    private static final Pattern REVISION = Pattern.compile("^\"?c:\\{[0-9A-Fa-f-]+},(\\d+)\"?$");

    private SharePointCTag() {
    }

    /**
     * @return the trailing revision number, or {@code null} if {@code cTag} is null or doesn't
     *     match the expected shape — a future change to Graph's format should degrade to "no
     *     revision shown", not throw.
     */
    public static Integer parseRevision(String cTag) {
        if (cTag == null) {
            return null;
        }
        Matcher matcher = REVISION.matcher(cTag.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
