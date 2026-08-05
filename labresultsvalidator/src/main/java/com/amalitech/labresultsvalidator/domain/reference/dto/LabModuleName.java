package com.amalitech.labresultsvalidator.domain.reference.dto;

/**
 * A lab title and the module that owns it, for grouping digest rows by module (C3 AC2).
 *
 * <p>Keyed by title rather than id because a rejected row usually has no resolved lab — validation
 * returns at its first failing check, often before the lab is looked up at all.
 */
public record LabModuleName(String labTitle, String moduleName) {
}
