package com.amalitech.labresultsvalidator.domain.standup.gate;

import com.amalitech.labresultsvalidator.domain.reference.entity.Lab;
import com.amalitech.labresultsvalidator.domain.reference.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.reference.entity.Learner;
import com.amalitech.labresultsvalidator.domain.reference.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.SpecializationRepository;
import com.amalitech.labresultsvalidator.domain.standup.service.Gate4EventService;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Component
public class Gate4ScoreSheetValidator {

    private static final Logger LOG = LoggerFactory.getLogger(Gate4ScoreSheetValidator.class);

    private final GraphDriveService graphDriveService;
    private final LearnerRepository learnerRepository;
    private final SpecializationRepository specializationRepository;
    private final LabModuleRepository labModuleRepository;
    private final LabRepository labRepository;

    public Gate4ScoreSheetValidator(
        GraphDriveService graphDriveService,
        LearnerRepository learnerRepository,
        SpecializationRepository specializationRepository,
        LabModuleRepository labModuleRepository,
        LabRepository labRepository
    ) {
        this.graphDriveService = graphDriveService;
        this.learnerRepository = learnerRepository;
        this.specializationRepository = specializationRepository;
        this.labModuleRepository = labModuleRepository;
        this.labRepository = labRepository;
    }

    public Gate4Result validate(String driveId, String scoresFolderItemId, UUID cohortId,
                                UUID jobId, Gate4EventService eventService) {
        List<DriveItemInfo> scoreFolderChildren;
        try {
            scoreFolderChildren = graphDriveService.listChildren(driveId, scoresFolderItemId);
        } catch (GraphAccessException ex) {
            return new Gate4Result(GateResult.fail(null, null, "G4-ACCESS",
                "Cannot list scores folder contents."));
        }

        LOG.info("[gate4] scores folder contains {} item(s): {}", scoreFolderChildren.size(),
            scoreFolderChildren.stream()
                .map(i -> (i.isFolder() ? "[DIR] " : "[FILE] ") + i.name())
                .collect(Collectors.toList()));

        // Lab Scores may contain scenario subfolders or score sheets directly (production layout).
        List<DriveItemInfo> xlsxFiles = new ArrayList<>();
        List<GateError> accessErrors = new ArrayList<>();
        for (DriveItemInfo item : scoreFolderChildren) {
            if (item.isFolder()) {
                LOG.info("[gate4] enumerating scenario folder '{}'", item.name());
                try {
                    List<DriveItemInfo> scenarioChildren =
                        graphDriveService.listChildren(driveId, item.itemId());
                    for (DriveItemInfo child : scenarioChildren) {
                        if (!child.isFolder() && child.name() != null
                                && child.name().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                            xlsxFiles.add(child);
                        }
                    }
                } catch (GraphAccessException ex) {
                    accessErrors.add(new GateError(item.name(), null, "G4-ACCESS",
                        "Cannot list scenario subfolder '" + item.name() + "': " + ex.getMessage()));
                }
            } else if (item.name() != null
                    && item.name().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                LOG.debug("[gate4] found score sheet directly in scores folder: '{}'", item.name());
                xlsxFiles.add(item);
            }
        }
        if (!accessErrors.isEmpty()) {
            return new Gate4Result(GateResult.fail(accessErrors));
        }

        LOG.info("[gate4] found {} score sheet(s): {}", xlsxFiles.size(),
            xlsxFiles.stream().map(DriveItemInfo::name).collect(Collectors.toList()));

        // Load all learners for this cohort once — used for NSP name/specialization lookup across all files.
        Map<String, Learner> learnersByName = learnerRepository.findAllByCohortId(cohortId).stream()
            .collect(Collectors.toMap(
                l -> l.getFullName().trim().toLowerCase(Locale.ROOT),
                l -> l,
                (a, b) -> a
            ));

        // Maps a configured lab's title to the specialization(s) it's configured under, so a row's
        // Lab Title can be checked against the reference data and cross-referenced with the NSP's specialization.
        Map<String, Set<UUID>> labTitleToSpecIds = buildLabTitleToSpecIds(cohortId);

        List<GateError> allErrors = new ArrayList<>();

        // Download every file concurrently (the dominant latency cost), then process results
        // sequentially in original order below — event emission and error aggregation are
        // identical to a fully-serial run.
        List<DownloadResult> downloads = prefetchDownloads(driveId, xlsxFiles);

        for (DownloadResult download : downloads) {
            DriveItemInfo file = download.file();
            eventService.emit(jobId, "file.start", Map.of("file", file.name()));

            if (download.error() != null) {
                GateError err = new GateError(file.name(), null, "G4-DOWNLOAD-FAIL",
                    "Could not download score file '" + file.name() + "': " + download.error().getMessage());
                allErrors.add(err);
                eventService.emit(jobId, "file.failed", Map.of(
                    "file", file.name(),
                    "errors", List.of(err.message())
                ));
                continue;
            }

            List<GateError> fileErrors =
                processScoreFile(file.name(), download.bytes(), learnersByName, labTitleToSpecIds);
            if (fileErrors.isEmpty()) {
                eventService.emit(jobId, "file.passed", Map.of("file", file.name()));
            } else {
                allErrors.addAll(fileErrors);
                eventService.emit(jobId, "file.failed", Map.of(
                    "file", file.name(),
                    "errors", fileErrors.stream()
                        .map(e -> "[" + e.file() + " | " + e.location() + "] " + e.message())
                        .collect(Collectors.toList())
                ));
            }
        }

        if (!allErrors.isEmpty()) {
            return new Gate4Result(GateResult.fail(allErrors));
        }
        return new Gate4Result(GateResult.pass());
    }

    private record DownloadResult(DriveItemInfo file, byte[] bytes, GraphAccessException error) {
    }

    private List<DownloadResult> prefetchDownloads(String driveId, List<DriveItemInfo> xlsxFiles) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DownloadResult>> futures = xlsxFiles.stream()
                .map(file -> executor.submit(() -> downloadOne(driveId, file)))
                .toList();
            List<DownloadResult> results = new ArrayList<>(futures.size());
            for (Future<DownloadResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while downloading score sheets", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Unexpected error downloading score sheets", ex.getCause());
        }
    }

    private DownloadResult downloadOne(String driveId, DriveItemInfo file) {
        try {
            return new DownloadResult(file, graphDriveService.downloadFile(driveId, file.itemId()), null);
        } catch (GraphAccessException ex) {
            return new DownloadResult(file, null, ex);
        }
    }

    // A lab title may be configured under more than one specialization (shared lab), so each
    // title maps to the set of specialization IDs it's valid under rather than a single one.
    private Map<String, Set<UUID>> buildLabTitleToSpecIds(UUID cohortId) {
        List<Specialization> specializations = specializationRepository.findAllByCohortId(cohortId);
        List<UUID> specIds = specializations.stream().map(Specialization::getId).collect(Collectors.toList());

        List<LabModule> modules = labModuleRepository.findAllBySpecializationIdIn(specIds);
        Map<UUID, UUID> specIdByModuleId = modules.stream()
            .collect(Collectors.toMap(LabModule::getId, LabModule::getSpecializationId));

        List<UUID> moduleIds = modules.stream().map(LabModule::getId).collect(Collectors.toList());
        List<Lab> labs = labRepository.findAllByModuleIdIn(moduleIds);

        Map<String, Set<UUID>> labTitleToSpecIds = new HashMap<>();
        for (Lab lab : labs) {
            UUID specId = specIdByModuleId.get(lab.getModuleId());
            if (specId == null) {
                continue;
            }
            labTitleToSpecIds
                .computeIfAbsent(lab.getTitle().trim().toLowerCase(Locale.ROOT), k -> new HashSet<>())
                .add(specId);
        }
        return labTitleToSpecIds;
    }

    private List<GateError> processScoreFile(
        String fileName,
        byte[] bytes,
        Map<String, Learner> learnersByName,
        Map<String, Set<UUID>> labTitleToSpecIds
    ) {
        List<GateError> errors = new ArrayList<>();

        // ZipSecureFile limits are JVM-global and are now applied once at startup by
        // PoiHardeningConfig. Setting them here per file meant this loop silently decided the
        // zip-bomb policy for every other POI caller in the process — including a
        // setMinInflateRatio(0) that disabled the guard outright (risk R-10).
        Workbook wb;
        try {
            wb = WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            LOG.warn("Failed to parse score workbook {}: {}", fileName, ex.getMessage());
            errors.add(new GateError(fileName, null, "G4-PARSE-FAIL",
                "Could not parse score workbook '" + fileName + "': " + ex.getMessage()));
            return errors;
        } catch (Exception ex) {
            LOG.warn("Unexpected error parsing score workbook {}: {}", fileName, ex.getMessage());
            errors.add(new GateError(fileName, null, "G4-PARSE-FAIL",
                "Unexpected error reading '" + fileName + "': " + ex.getMessage()));
            return errors;
        }

        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            Sheet sheet = wb.getSheetAt(s);
            String sheetName = sheet.getSheetName();

            if (ScoreSheetRowReader.SKIP_SHEETS.contains(sheetName.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }

            int headerRowIdx = ScoreSheetRowReader.findHeaderRowIndex(sheet);
            if (headerRowIdx < 0) {
                errors.add(new GateError(fileName, "sheet " + sheetName, "G4-HEADER-NOT-FOUND",
                    "Could not locate a header row with the required columns in sheet '" + sheetName + "'."));
                continue;
            }
            Map<String, Integer> headers = ScoreSheetRowReader.readHeadersFromRow(sheet.getRow(headerRowIdx));
            List<GateError> colErrors = checkRequiredColumns(fileName, sheetName, headers);
            if (!colErrors.isEmpty()) {
                errors.addAll(colErrors);
                continue;
            }

            int nspCol = headers.get("name of nsp");
            int labTitleCol = headers.get("lab title");

            for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (ScoreSheetRowReader.isBlankRow(row)) {
                    continue;
                }
                int rowNum = i + 1;
                String location = "sheet " + sheetName + " row " + rowNum;

                String nspName = ScoreSheetRowReader.getCellString(row, nspCol);
                String labTitle = ScoreSheetRowReader.getCellString(row, labTitleCol);

                Learner learner = null;
                if (nspName == null || nspName.isBlank()) {
                    errors.add(new GateError(fileName, location, "G4-BLANK-NSP", "Name of NSP is blank."));
                } else {
                    learner = learnersByName.get(nspName.trim().toLowerCase(Locale.ROOT));
                    if (learner == null) {
                        errors.add(new GateError(fileName, location, "G4-UNKNOWN-NSP",
                            "NSP '" + nspName + "' does not match any learner in this cohort."));
                    }
                }

                if (labTitle == null || labTitle.isBlank()) {
                    errors.add(new GateError(fileName, location, "G4-BLANK-LAB-TITLE", "Lab Title is blank."));
                } else {
                    Set<UUID> specIdsForLab = labTitleToSpecIds.get(labTitle.trim().toLowerCase(Locale.ROOT));
                    if (specIdsForLab == null) {
                        errors.add(new GateError(fileName, location, "G4-UNKNOWN-LAB",
                            "Lab Title '" + labTitle + "' does not match any lab configured for this cohort."));
                    } else if (learner != null && !specIdsForLab.contains(learner.getSpecializationId())) {
                        errors.add(new GateError(fileName, location, "G4-SPEC-MISMATCH",
                            "NSP '" + nspName + "' specialization does not match the specialization "
                                + "configured for lab '" + labTitle + "'."));
                    }
                }
            }
        }
        return errors;
    }

    private List<GateError> checkRequiredColumns(
            String fileName, String sheetName, Map<String, Integer> headers) {
        List<GateError> errors = new ArrayList<>();
        for (String col : ScoreSheetRowReader.findMissingColumns(headers)) {
            errors.add(new GateError(fileName, "sheet " + sheetName, "G4-MISSING-COLUMN",
                "Required column '" + col + "' not found in sheet '"
                    + sheetName + "' in file '" + fileName + "'."));
        }
        return errors;
    }
}
