/**
 * Generic, reusable CSV parsing and writing foundation built on OpenCSV.
 *
 * <p>Domain features (learner roster, lab-result scores, cohorts, specializations, modules,
 * labs) declare an OpenCSV-annotated POJO and delegate parsing to
 * {@link com.amalitech.labresultsvalidator.common.csv.CsvParserService} and writing to
 * {@link com.amalitech.labresultsvalidator.common.csv.CsvWriterService}. The core handles
 * header validation, row-cap enforcement, partial-success row collection, and template/export
 * generation; consumers own all domain-specific validation.
 */
package com.amalitech.labresultsvalidator.common.csv;
