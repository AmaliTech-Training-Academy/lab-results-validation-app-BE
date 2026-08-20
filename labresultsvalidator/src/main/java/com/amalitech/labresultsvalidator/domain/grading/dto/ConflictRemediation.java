package com.amalitech.labresultsvalidator.domain.grading.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tells the admin where the duplicate physically is and that the durable fix is in the workbook.
 *
 * <p>Resolving a conflict in the app settles which mark counts; it cannot delete the extra row from
 * the sheet, because nothing is written back to SharePoint. So the same duplicate is read on every
 * subsequent run, and until now the queue never said so — an admin resolving one had no way to know
 * they were treating a symptom. Every fact needed for the sentence is already in the stored payload.
 *
 * <p>Same intent as {@link RejectionRuleDescriptions}: turn what we stored into something a
 * non-engineer can act on.
 */
public final class ConflictRemediation {

    private ConflictRemediation() {
    }

    /** Null when there is nothing locatable to point at (empty or corrupt payload). */
    public static String describe(List<ConflictCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        Set<String> files = candidates.stream()
            .map(ConflictCandidate::fileName)
            .filter(f -> f != null && !f.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> sheets = candidates.stream()
            .map(ConflictCandidate::sheetName)
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> rows = candidates.stream()
            .map(ConflictCandidate::rowNum)
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .toList();

        if (files.isEmpty() || rows.isEmpty()) {
            return null;
        }

        String where = files.size() == 1 && sheets.size() == 1
            ? "in " + files.iterator().next() + ", sheet " + sheets.iterator().next()
            : "across " + String.join(" and ", files);

        return "This duplicate is " + where + ", " + rowPhrase(rows) + ". Remove the extra row there to "
            + "fix it permanently — resolving it here settles which mark counts for these rows, and the "
            + "duplicate is only raised again if its rows or marks change in the sheet.";
    }

    /** "row 5" / "rows 5 and 15" / "rows 5, 15 and 20". */
    private static String rowPhrase(List<String> rows) {
        if (rows.size() == 1) {
            return "row " + rows.get(0);
        }
        List<String> head = new ArrayList<>(rows.subList(0, rows.size() - 1));
        return "rows " + String.join(", ", head) + " and " + rows.get(rows.size() - 1);
    }
}
