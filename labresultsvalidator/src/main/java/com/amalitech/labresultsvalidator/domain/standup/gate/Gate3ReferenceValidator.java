package com.amalitech.labresultsvalidator.domain.standup.gate;

import com.amalitech.labresultsvalidator.common.utils.SpecializationNameMatcher;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.RecordFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class Gate3ReferenceValidator {

    private static final Logger LOG = LoggerFactory.getLogger(Gate3ReferenceValidator.class);

    // Real sheets may have a title block above the header row — scan the first few rows
    // and pick the one that best matches the columns we require, rather than assuming row 0.
    private static final int HEADER_SCAN_LIMIT = 10;

    private static final List<String> SPEC_COLUMNS = List.of("specializationid", "specialization");
    private static final List<String> MODULE_COLUMNS =
        List.of("specializationid", "moduleid", "module name");
    private static final List<String> LAB_COLUMNS = List.of("moduleid", "assessmentid", "lab title");
    private static final List<String> LEARNER_COLUMNS =
        List.of("amalitech email", "full name", "specialization");
    private static final List<String> INSTRUCTOR_COLUMNS =
        List.of("name", "email", "specialization");

    private final GraphDriveService graphDriveService;
    private final SharePointProperties sharePointProperties;
    private final InstructorContactRepository instructorContactRepository;

    public Gate3ReferenceValidator(GraphDriveService graphDriveService, SharePointProperties sharePointProperties,
                                    InstructorContactRepository instructorContactRepository) {
        this.graphDriveService = graphDriveService;
        this.sharePointProperties = sharePointProperties;
        this.instructorContactRepository = instructorContactRepository;
    }

    public Gate3Result validate(String driveId, String referenceFolderItemId) {
        List<DriveItemInfo> refChildren;
        try {
            refChildren = graphDriveService.listChildren(driveId, referenceFolderItemId);
        } catch (GraphAccessException ex) {
            LOG.error("[gate3] could not list children of driveId={} itemId={}: {}",
                driveId, referenceFolderItemId, ex.getMessage(), ex);
            return new Gate3Result(
                GateResult.fail(null, null, "G3-ACCESS",
                    "Cannot list reference folder contents."),
                null
            );
        }

        // Key by lowercase name so all lookups are case-insensitive.
        Map<String, DriveItemInfo> childByName = refChildren.stream()
            .collect(Collectors.toMap(
                d -> d.name().toLowerCase(Locale.ROOT),
                Function.identity(),
                (a, b) -> a
            ));

        LOG.info("[gate3] files found in reference folder: {}", childByName.keySet());

        // expectedRefFileNames() contains only the 4 required files (quiz reference is optional).
        List<String> expected = sharePointProperties.expectedRefFileNames();
        List<GateError> missingErrors = new ArrayList<>();
        for (String fname : expected) {
            if (!childByName.containsKey(fname.toLowerCase(Locale.ROOT))) {
                missingErrors.add(new GateError(fname, null, "G3-MISSING-FILE",
                    "Required reference file '" + fname + "' not found in the reference folder. "
                        + "Found: " + childByName.keySet()));
            }
        }
        if (!missingErrors.isEmpty()) {
            return new Gate3Result(GateResult.fail(missingErrors), null);
        }

        String specsFile = sharePointProperties.refFiles().specializations();
        String modulesFile = sharePointProperties.refFiles().modules();
        String labsFile = sharePointProperties.refFiles().labs();
        String learnersFile = sharePointProperties.refFiles().learners();
        String instructorsFile = sharePointProperties.refFiles().instructors();
        boolean instructorsFilePresent = childByName.containsKey(instructorsFile.toLowerCase(Locale.ROOT));

        Map<String, byte[]> fileBytes = new HashMap<>();
        List<GateError> downloadErrors = new ArrayList<>();
        for (String fname : expected) {
            DriveItemInfo info = childByName.get(fname.toLowerCase(Locale.ROOT));
            try {
                fileBytes.put(fname, graphDriveService.downloadFile(driveId, info.itemId()));
            } catch (GraphAccessException ex) {
                LOG.error("[gate3] could not download reference file '{}' (itemId={}): {}",
                    fname, info.itemId(), ex.getMessage(), ex);
                downloadErrors.add(new GateError(fname, null, "G3-DOWNLOAD-FAIL",
                    "Could not download reference file '" + fname + "': " + ex.getMessage()));
            }
        }
        // Instructors file is optional — only attempt a download if it's actually present.
        if (instructorsFilePresent) {
            DriveItemInfo info = childByName.get(instructorsFile.toLowerCase(Locale.ROOT));
            try {
                fileBytes.put(instructorsFile, graphDriveService.downloadFile(driveId, info.itemId()));
            } catch (GraphAccessException ex) {
                LOG.error("[gate3] could not download instructors file '{}' (itemId={}): {}",
                    instructorsFile, info.itemId(), ex.getMessage(), ex);
                downloadErrors.add(new GateError(instructorsFile, null, "G3-DOWNLOAD-FAIL",
                    "Could not download reference file '" + instructorsFile + "': " + ex.getMessage()));
            }
        }
        if (!downloadErrors.isEmpty()) {
            return new Gate3Result(GateResult.fail(downloadErrors), null);
        }

        List<GateError> allErrors = new ArrayList<>();

        List<ValidatedReferenceBundle.SpecializationRow> specializations =
            validateSpecializations(specsFile, fileBytes.get(specsFile), allErrors);

        Set<String> validSpecIds = specializations.stream()
            .map(ValidatedReferenceBundle.SpecializationRow::specializationId)
            .collect(Collectors.toSet());

        // Spec names are used to cross-reference the trainee database (which links by name).
        Map<String, String> specNamesByNormalized = specializations.stream()
            .collect(Collectors.toMap(
                r -> SpecializationNameMatcher.normalize(r.name()),
                ValidatedReferenceBundle.SpecializationRow::name,
                (a, b) -> a));

        List<ValidatedReferenceBundle.ModuleRow> modules =
            validateModules(modulesFile, fileBytes.get(modulesFile), validSpecIds, allErrors);

        Set<String> validModuleIds = modules.stream()
            .map(ValidatedReferenceBundle.ModuleRow::moduleId)
            .collect(Collectors.toSet());

        List<ValidatedReferenceBundle.LabRow> labs =
            validateLabs(labsFile, fileBytes.get(labsFile), validModuleIds, allErrors);

        List<ValidatedReferenceBundle.LearnerRow> learners =
            validateLearners(learnersFile, fileBytes.get(learnersFile), specNamesByNormalized, allErrors);

        // Instructors file stays optional (excluded from expectedRefFileNames()) — absent means an
        // empty instructor list, same as before this file was actually parsed. Present-but-malformed
        // hard-fails Gate 3 like every sibling reference sheet.
        List<ValidatedReferenceBundle.InstructorRow> instructors = instructorsFilePresent
            ? validateInstructors(instructorsFile, fileBytes.get(instructorsFile), specNamesByNormalized, allErrors)
            : List.of();

        if (!allErrors.isEmpty()) {
            return new Gate3Result(GateResult.fail(allErrors), null);
        }

        LOG.info("[gate3] optional instructors reference file '{}' {}",
            instructorsFile, instructorsFilePresent ? "found, " + instructors.size() + " row(s)" : "not present");

        return new Gate3Result(GateResult.pass(),
            new ValidatedReferenceBundle(specializations, modules, labs, learners, instructors));
    }

    // Columns: specializationid, specialization
    private List<ValidatedReferenceBundle.SpecializationRow> validateSpecializations(
            String fileName, byte[] bytes, List<GateError> errors) {
        List<ValidatedReferenceBundle.SpecializationRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) {
            return rows;
        }

        int headerRowIdx = findHeaderRowIndex(sheet, SPEC_COLUMNS);
        if (headerRowIdx < 0) {
            errors.add(new GateError(fileName, null, "G3-HEADER-NOT-FOUND",
                "Could not locate a header row with the required columns in '" + fileName + "'."));
            return rows;
        }
        Map<String, Integer> headers = readHeaders(sheet, headerRowIdx);
        List<GateError> colErrors = checkRequiredColumns(fileName, headers, "specializationid", "specialization");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        Set<String> seenIds = new HashSet<>();
        for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) {
                continue;
            }
            int rowNum = i + 1;
            String specId = getCellString(row, headers.get("specializationid"));
            String name = getCellString(row, headers.get("specialization"));

            if (specId == null || specId.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-SPEC-ID",
                    "Specialization ID is blank."));
                continue;
            }
            if (name == null || name.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-SPEC-NAME",
                    "Specialization name is blank."));
                continue;
            }
            if (!seenIds.add(specId)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-DUP-SPEC-ID",
                    "Duplicate specialization ID '" + specId + "'."));
                continue;
            }
            rows.add(new ValidatedReferenceBundle.SpecializationRow(specId, name));
        }
        return rows;
    }

    // Columns: specializationid, moduleid, module name. No phase — a module's position within
    // its specialization plays no role anywhere; rows resolve by (Lab Title, specialization).
    private List<ValidatedReferenceBundle.ModuleRow> validateModules(
            String fileName, byte[] bytes, Set<String> validSpecIds, List<GateError> errors) {
        List<ValidatedReferenceBundle.ModuleRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) {
            return rows;
        }

        int headerRowIdx = findHeaderRowIndex(sheet, MODULE_COLUMNS);
        if (headerRowIdx < 0) {
            errors.add(new GateError(fileName, null, "G3-HEADER-NOT-FOUND",
                "Could not locate a header row with the required columns in '" + fileName + "'."));
            return rows;
        }
        Map<String, Integer> headers = readHeaders(sheet, headerRowIdx);
        List<GateError> colErrors = checkRequiredColumns(
            fileName, headers, "specializationid", "moduleid", "module name");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        Set<String> seenModuleIds = new HashSet<>();
        for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) {
                continue;
            }
            int rowNum = i + 1;
            String specId = getCellString(row, headers.get("specializationid"));
            String moduleId = getCellString(row, headers.get("moduleid"));
            String name = getCellString(row, headers.get("module name"));

            if (moduleId == null || moduleId.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-MODULE-ID",
                    "Module ID is blank."));
                continue;
            }
            if (name == null || name.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-MODULE-NAME",
                    "Module name is blank."));
                continue;
            }
            if (specId == null || !validSpecIds.contains(specId)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-UNKNOWN-SPEC-ID",
                    "Specialization ID '" + specId + "' not found in Specializations file."));
                continue;
            }
            if (!seenModuleIds.add(moduleId)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-DUP-MODULE-ID",
                    "Duplicate module ID '" + moduleId + "'."));
                continue;
            }
            rows.add(new ValidatedReferenceBundle.ModuleRow(moduleId, name, specId));
        }
        return rows;
    }

    // Columns: moduleid, module name, assessmentid, lab title
    private List<ValidatedReferenceBundle.LabRow> validateLabs(
            String fileName, byte[] bytes, Set<String> validModuleIds, List<GateError> errors) {
        List<ValidatedReferenceBundle.LabRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) {
            return rows;
        }

        int headerRowIdx = findHeaderRowIndex(sheet, LAB_COLUMNS);
        if (headerRowIdx < 0) {
            errors.add(new GateError(fileName, null, "G3-HEADER-NOT-FOUND",
                "Could not locate a header row with the required columns in '" + fileName + "'."));
            return rows;
        }
        Map<String, Integer> headers = readHeaders(sheet, headerRowIdx);
        List<GateError> colErrors = checkRequiredColumns(
            fileName, headers, "moduleid", "assessmentid", "lab title");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        Set<String> seenAssessmentIds = new HashSet<>();
        for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) {
                continue;
            }
            int rowNum = i + 1;
            String moduleId = getCellString(row, headers.get("moduleid"));
            String assessmentId = getCellString(row, headers.get("assessmentid"));
            String labTitle = getCellString(row, headers.get("lab title"));

            if (labTitle == null || labTitle.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-LAB-TITLE",
                    "Lab title is blank."));
                continue;
            }
            if (assessmentId == null || assessmentId.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-ASSESSMENT-ID",
                    "Assessment ID is blank."));
                continue;
            }
            if (!seenAssessmentIds.add(assessmentId)) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-DUP-ASSESSMENT-ID",
                    "Duplicate assessment ID '" + assessmentId + "'."));
                continue;
            }
            if (moduleId == null || !validModuleIds.contains(moduleId)) {
                // Module may be absent from this cohort's Module Setup — skip rather than fail.
                LOG.warn("[gate3] {} row {} — lab '{}' references unknown moduleId '{}', skipping",
                    fileName, rowNum, labTitle, moduleId);
                continue;
            }
            rows.add(new ValidatedReferenceBundle.LabRow(assessmentId, labTitle, moduleId));
        }
        return rows;
    }

    // Columns: amalitech email, full name, specialization
    private List<ValidatedReferenceBundle.LearnerRow> validateLearners(
            String fileName, byte[] bytes, Map<String, String> specNamesByNormalized, List<GateError> errors) {
        List<ValidatedReferenceBundle.LearnerRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) {
            return rows;
        }

        int headerRowIdx = findHeaderRowIndex(sheet, LEARNER_COLUMNS);
        if (headerRowIdx < 0) {
            errors.add(new GateError(fileName, null, "G3-HEADER-NOT-FOUND",
                "Could not locate a header row with the required columns in '" + fileName + "'."));
            return rows;
        }
        Map<String, Integer> headers = readHeaders(sheet, headerRowIdx);
        List<GateError> colErrors = checkRequiredColumns(
            fileName, headers, "amalitech email", "full name", "specialization");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        Set<String> seenEmails = new HashSet<>();
        for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) {
                continue;
            }
            int rowNum = i + 1;
            String email = getCellString(row, headers.get("amalitech email"));
            String fullName = getCellString(row, headers.get("full name"));
            String specialization = getCellString(row, headers.get("specialization"));

            boolean rowHasError = false;

            if (email == null || email.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-TRAINEE-EMAIL",
                    "Email is blank."));
                rowHasError = true;
            } else if (!seenEmails.add(email.toLowerCase(Locale.ROOT))) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-DUP-TRAINEE-EMAIL",
                    "Duplicate email '" + email + "'."));
                rowHasError = true;
            }

            SpecializationNameMatcher.MatchResult<String> specMatch =
                SpecializationNameMatcher.resolve(specialization, specNamesByNormalized);
            switch (specMatch.outcome()) {
                case NO_MATCH -> {
                    errors.add(new GateError(fileName, "row " + rowNum, "G3-UNKNOWN-SPEC-NAME",
                        "Specialization '" + specialization
                            + "' does not match any entry in Specializations file."));
                    rowHasError = true;
                }
                case AMBIGUOUS -> {
                    errors.add(new GateError(fileName, "row " + rowNum, "G3-AMBIGUOUS-SPEC-NAME",
                        "Specialization '" + specialization
                            + "' matches more than one entry in Specializations file; "
                            + "use the exact specialization name."));
                    rowHasError = true;
                }
                case MATCHED -> { }
                default -> { }
            }

            if (!rowHasError) {
                rows.add(new ValidatedReferenceBundle.LearnerRow(email, fullName, specialization));
            }
        }
        return rows;
    }

    // Columns:  name, email, specialization. The same email legitimately repeats across rows
    // with different specialization values (an instructor teaching across specializations), so
    // duplicates are checked on the (email, specialization) pair, not email alone.
    private List<ValidatedReferenceBundle.InstructorRow> validateInstructors(
            String fileName, byte[] bytes, Map<String, String> specNamesByNormalized, List<GateError> errors) {
        List<ValidatedReferenceBundle.InstructorRow> rows = new ArrayList<>();
        Sheet sheet = openFirstSheet(fileName, bytes, errors);
        if (sheet == null) {
            return rows;
        }

        int headerRowIdx = findHeaderRowIndex(sheet, INSTRUCTOR_COLUMNS);
        if (headerRowIdx < 0) {
            errors.add(new GateError(fileName, null, "G3-HEADER-NOT-FOUND",
                "Could not locate a header row with the required columns in '" + fileName + "'."));
            return rows;
        }
        Map<String, Integer> headers = readHeaders(sheet, headerRowIdx);
        List<GateError> colErrors = checkRequiredColumns(
            fileName, headers, "name", "email", "specialization");
        if (!colErrors.isEmpty()) {
            errors.addAll(colErrors);
            return rows;
        }

        Set<String> seenEmailSpecPairs = new HashSet<>();
        for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlankRow(row)) {
                continue;
            }
            int rowNum = i + 1;
            String fullName = getCellString(row, headers.get("name"));
            String email = getCellString(row, headers.get("email"));
            String specialization = getCellString(row, headers.get("specialization"));

            boolean rowHasError = false;

            if (fullName == null || fullName.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-INSTRUCTOR-NAME",
                    "Full name is blank."));
                rowHasError = true;
            }
            if (email == null || email.isBlank()) {
                errors.add(new GateError(fileName, "row " + rowNum, "G3-BLANK-INSTRUCTOR-EMAIL",
                    "Email is blank."));
                rowHasError = true;
            }

            String matchedSpecName = null;
            SpecializationNameMatcher.MatchResult<String> specMatch =
                SpecializationNameMatcher.resolve(specialization, specNamesByNormalized);
            switch (specMatch.outcome()) {
                case NO_MATCH -> {
                    errors.add(new GateError(fileName, "row " + rowNum, "G3-UNKNOWN-SPEC-NAME",
                        "Specialization '" + specialization
                            + "' does not match any entry in Specializations file."));
                    rowHasError = true;
                }
                case AMBIGUOUS -> {
                    errors.add(new GateError(fileName, "row " + rowNum, "G3-AMBIGUOUS-SPEC-NAME",
                        "Specialization '" + specialization
                            + "' matches more than one entry in Specializations file; "
                            + "use the exact specialization name."));
                    rowHasError = true;
                }
                case MATCHED -> matchedSpecName = specMatch.value();
                default -> { }
            }

            if (!rowHasError) {
                String pairKey = email.trim().toLowerCase(Locale.ROOT) + "|" + matchedSpecName;
                if (!seenEmailSpecPairs.add(pairKey)) {
                    errors.add(new GateError(fileName, "row " + rowNum, "G3-DUP-INSTRUCTOR-SPECIALIZATION",
                        "Instructor '" + email + "' is already listed for specialization '"
                            + matchedSpecName + "'."));
                    rowHasError = true;
                }
            }

            // FND-54 / RTM A6-AC2 — instructor_contacts is global, not cohort-scoped, and is
            // upserted by full_name at Accept (ReferenceCommitService.persistInstructors): a name
            // this DB has never seen is created outright, carrying whatever email this file gives
            // it. email is separately UNIQUE, so if that email already belongs to a different
            // instructor from another cohort, Accept's INSERT is the first thing to notice —
            // failing with a raw, unnamed 409. Catch it here instead, where the file, row, email
            // and both names are all still on hand to name in the error. A match under the SAME
            // name is not a conflict — it's this instructor's existing row, e.g. this cohort's own
            // prior successful run.
            if (!rowHasError) {
                Optional<InstructorContact> existingByEmail =
                    instructorContactRepository.findByEmailIgnoreCase(email.trim());
                if (existingByEmail.isPresent()
                        && !existingByEmail.get().getFullName().equalsIgnoreCase(fullName.trim())) {
                    errors.add(new GateError(fileName, "row " + rowNum, "G3-INSTRUCTOR-EMAIL-CONFLICT",
                        "Email '" + email + "' is already registered to instructor '"
                            + existingByEmail.get().getFullName() + "' from another cohort; '"
                            + fileName + "' lists the same email under a different name, '"
                            + fullName + "'. Confirm which name is correct and fix the file before "
                            + "accepting."));
                    rowHasError = true;
                }
            }

            if (!rowHasError) {
                rows.add(new ValidatedReferenceBundle.InstructorRow(fullName, email, matchedSpecName));
            }
        }
        return rows;
    }

    /**
     * Opens the workbook and returns its first sheet. The catch here is scoped to
     * {@code WorkbookFactory.create} alone (not the {@code getSheetAt} call below it) and to
     * the specific exception family POI uses to signal "this isn't a valid/openable workbook" —
     * {@link IOException} plus the unchecked {@link EncryptedDocumentException} /
     * {@link UnsupportedFileFormatException} (covers {@code NotOfficeXmlFileException},
     * {@code OldFileFormatException}, {@code EmptyFileException}) / {@link RecordFormatException}
     * / {@link POIXMLException} family. A bug elsewhere (a bad cast, an NPE in
     * {@code getSheetAt}) must not be relabelled as "corrupt reference file" — it should
     * propagate so the pipeline reports it as the unexpected failure it actually is.
     */
    private Sheet openFirstSheet(String fileName, byte[] bytes, List<GateError> errors) {
        // ZipSecureFile limits are JVM-global and are applied once at startup by
        // PoiHardeningConfig. Setting them here per file meant this loop silently decided the
        // zip-bomb policy for every other POI caller in the process — including a
        // setMinInflateRatio(0) that disabled the guard outright (risk R-10). See Gate4ScoreSheetValidator.
        Workbook wb;
        try {
            wb = WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (IOException | EncryptedDocumentException | UnsupportedFileFormatException
                 | RecordFormatException | POIXMLException ex) {
            LOG.warn("Failed to parse workbook {}: {}", fileName, ex.getMessage());
            errors.add(new GateError(fileName, null, "G3-PARSE-FAIL",
                "Could not parse workbook '" + fileName + "': " + ex.getMessage()));
            return null;
        }

        Sheet sheet = wb.getSheetAt(0);
        if (sheet == null) {
            errors.add(new GateError(fileName, null, "G3-EMPTY-WORKBOOK",
                "Workbook '" + fileName + "' has no sheets."));
        }
        return sheet;
    }

    // Scans the first few rows and returns the index of the one with the most required-column matches,
    // so a title block above the real header row doesn't get mistaken for it. Returns -1 if no row in
    // the scan window matches any required column at all, so callers can report "no header row found"
    // instead of silently treating row 0 as the header and mis-locating every column.
    private int findHeaderRowIndex(Sheet sheet, List<String> requiredColumns) {
        int best = -1;
        long bestMatches = 0;
        int limit = Math.min(HEADER_SCAN_LIMIT, sheet.getLastRowNum() + 1);
        for (int r = 0; r < limit; r++) {
            Map<String, Integer> candidate = readHeaders(sheet, r);
            long matches = requiredColumns.stream().filter(candidate::containsKey).count();
            if (matches > bestMatches) {
                bestMatches = matches;
                best = r;
            }
        }
        return best;
    }

    // Headers are stored lowercase so all column name checks are case-insensitive.
    private Map<String, Integer> readHeaders(Sheet sheet, int rowIdx) {
        Map<String, Integer> headers = new HashMap<>();
        Row headerRow = sheet.getRow(rowIdx);
        if (headerRow == null) {
            return headers;
        }
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            String val = getCellString(headerRow, c);
            if (val != null && !val.isBlank()) {
                headers.put(val.trim().toLowerCase(Locale.ROOT), c);
            }
        }
        return headers;
    }

    private List<GateError> checkRequiredColumns(
            String fileName, Map<String, Integer> headers, String... required) {
        List<GateError> errors = new ArrayList<>();
        for (String col : required) {
            if (!headers.containsKey(col)) {
                errors.add(new GateError(fileName, null, "G3-MISSING-COLUMN",
                    "Required column '" + col + "' not found in '" + fileName + "'."));
            }
        }
        return errors;
    }

    private boolean isBlankRow(Row row) {
        if (row == null) {
            return true;
        }
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String val = getCellString(row, c);
                if (val != null && !val.isBlank()) {
                    return false;
                }
            }
        }
        return true;
    }

    private String getCellString(Row row, Integer colIndex) {
        if (row == null || colIndex == null) {
            return null;
        }
        Cell cell = row.getCell(colIndex);
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d)) {
                    yield String.valueOf((long) d);
                }
                yield String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCachedFormulaResultType() == CellType.STRING
                ? cell.getStringCellValue().trim()
                : String.valueOf(cell.getNumericCellValue());
            default -> null;
        };
    }
}
