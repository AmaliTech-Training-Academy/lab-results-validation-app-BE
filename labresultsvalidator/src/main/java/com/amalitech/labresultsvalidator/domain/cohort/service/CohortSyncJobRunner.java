package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.SystemUser;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncFile;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionRun;
import com.amalitech.labresultsvalidator.domain.cohort.entity.SyncFileChangeState;
import com.amalitech.labresultsvalidator.domain.cohort.ingestion.GradingIngestionService;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortSyncFileRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortSyncJobRepository;
import com.amalitech.labresultsvalidator.domain.cohort.sync.FetchOutcome;
import com.amalitech.labresultsvalidator.domain.cohort.sync.FetchedWorkbook;
import com.amalitech.labresultsvalidator.domain.cohort.sync.WorkbookFetchService;
import com.amalitech.labresultsvalidator.domain.cohort.sync.WorkbookParseException;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemDetails;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.amalitech.labresultsvalidator.infrastructure.storage.S3StorageService;
import com.amalitech.labresultsvalidator.infrastructure.storage.exception.S3StorageException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs one cohort's score-sheet sync: enumerates the "Lab Scores" folder, detects which
 * workbooks changed since the last run (B3), fetches and parses the changed ones (B4), and
 * archives them to S3.
 *
 * <p><b>Change detection.</b> Each workbook is always written to the same S3 key in a versioned
 * bucket, so the object at that key is the copy the previous run archived. Detection compares
 * SharePoint's current bytes against it — no stored fingerprint is consulted, and nothing needs
 * seeding on a first run.
 *
 * <p><b>The upload is a commit.</b> Uploading changed bytes is what advances that baseline, so it
 * happens <em>after</em> the processing step. If processing fails, the old baseline survives and
 * the next run re-detects the change and retries by itself.
 *
 * <p><b>Fail scope.</b> Per §4.5, a failure halts the smallest self-contained unit. One
 * unreadable workbook or unlistable scenario folder never stops its siblings, and one cohort's
 * job never affects another's (B2 AC3).
 *
 * <p>Note on B3 AC2: the AC describes an unchanged file as skipped with "no download". Comparing
 * against the S3 archive requires the bytes in hand, so the download always happens; the parse
 * and the upload are what get skipped. This is the agreed approach, not an oversight.
 */
@Service
@RequiredArgsConstructor
public class CohortSyncJobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CohortSyncJobRunner.class);
    private static final String XLSX_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String XLSX_SUFFIX = ".xlsx";

    private final CohortRepository cohortRepository;
    private final CohortSyncJobRepository syncJobRepository;
    private final CohortSyncFileRepository syncFileRepository;
    private final SyncEventService syncEventService;
    private final StandupSseRegistry sseRegistry;
    private final AuditEventService auditEventService;
    private final GraphDriveService graphDriveService;
    private final SharePointProperties sharePointProperties;
    private final S3StorageService s3StorageService;
    private final WorkbookFetchService workbookFetchService;
    private final GradingIngestionService gradingIngestionService;

    @Async("syncTaskExecutor")
    public void run(UUID cohortId, UUID jobId, UUID actorId) {
        LOG.info("[sync] job={} cohort={} STARTED", jobId, cohortId);
        CohortSyncJobStatus finalStatus = CohortSyncJobStatus.FAILED;
        SyncCounts counts = new SyncCounts();

        try {
            Cohort cohort = cohortRepository.findById(cohortId)
                .orElseThrow(() -> new IllegalStateException("Cohort " + cohortId + " not found"));

            String driveId = cohort.getSharepointDriveId();
            String parentItemId = cohort.getSharepointItemId();
            if (driveId == null || parentItemId == null) {
                throw new IllegalStateException("Cohort is missing SharePoint drive reference.");
            }

            List<String> itemIds = discoverScoreSheets(driveId, parentItemId, jobId);

            LOG.info("[sync] job={} discovered {} score sheet(s)", jobId, itemIds.size());

            // Fetch every file concurrently (the dominant latency cost — Graph metadata +
            // download round-trips), then process them sequentially in original order below so
            // event emission, counts and DB writes are identical to a fully-serial run.
            List<PrefetchResult> prefetched = prefetchAll(cohort, driveId, itemIds);

            // A human-attributed run is a manual trigger; an unattributed one came from the
            // scheduler (triggerScheduledSyncForCohort/triggerScheduledSyncForAll pass null).
            // triggerType reflects that distinction; downstream writes attribute the SYSTEM
            // pseudo-actor rather than leaving triggered_by/created_by/updated_by null (D1 AC3).
            String triggerType = actorId != null ? "MANUAL" : "SCHEDULED";
            UUID effectiveActorId = actorId != null ? actorId : SystemUser.ID;
            for (PrefetchResult result : prefetched) {
                processFile(cohort, jobId, result, counts, effectiveActorId, triggerType);
            }

            finalStatus = CohortSyncJobStatus.COMPLETED;
            auditEventService.record("SYNC_COMPLETED", cohortId, effectiveActorId, counts.toPayload(cohort.getName()));
            LOG.info("[sync] job={} cohort={} COMPLETED — {}", jobId, cohortId, counts);
        } catch (Exception ex) {
            LOG.error("[sync] job={} cohort={} FAILED: {}", jobId, cohortId, ex.getMessage(), ex);
        }

        Map<String, Object> donePayload = counts.toPayload(null);
        donePayload.put("status", finalStatus.name());
        syncEventService.emit(jobId, "sync.done", donePayload);
        sseRegistry.complete(jobId);

        CohortSyncJobStatus status = finalStatus;
        syncJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            job.setCompletedAt(OffsetDateTime.now());
            syncJobRepository.save(job);
        });
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /**
     * Collects the drive item ids of every {@code .xlsx} under the scores folder. "Lab Scores"
     * may hold sheets directly or group them into scenario subfolders (production layout), so
     * one level of nesting is traversed — the same shape Gate 4 walks.
     */
    private List<String> discoverScoreSheets(String driveId, String parentItemId, UUID jobId) {
        List<DriveItemInfo> children;
        try {
            children = graphDriveService.listChildren(driveId, parentItemId);
        } catch (GraphAccessException ex) {
            throw new IllegalStateException("Cannot list cohort folder: " + ex.getMessage(), ex);
        }

        String scoresFolderName = sharePointProperties.scoresFolder();
        String scoresFolderItemId = children.stream()
            .filter(c -> c.isFolder() && scoresFolderName.equalsIgnoreCase(c.name()))
            .map(DriveItemInfo::itemId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Scores folder '" + scoresFolderName + "' not found."));

        List<DriveItemInfo> scoreFolderChildren;
        try {
            scoreFolderChildren = graphDriveService.listChildren(driveId, scoresFolderItemId);
        } catch (GraphAccessException ex) {
            throw new IllegalStateException("Cannot list scores folder contents: " + ex.getMessage(), ex);
        }

        List<String> itemIds = new ArrayList<>();
        for (DriveItemInfo item : scoreFolderChildren) {
            if (item.isFolder()) {
                itemIds.addAll(listWorkbooksIn(driveId, item, jobId));
            } else if (isWorkbook(item.name())) {
                itemIds.add(item.itemId());
            }
        }
        return itemIds;
    }

    /**
     * Lists workbooks in one scenario subfolder. An unreadable subfolder is reported and skipped
     * rather than sinking the whole sweep — the other scenarios' sheets are still valid work.
     */
    private List<String> listWorkbooksIn(String driveId, DriveItemInfo folder, UUID jobId) {
        try {
            return graphDriveService.listChildren(driveId, folder.itemId()).stream()
                .filter(child -> !child.isFolder() && isWorkbook(child.name()))
                .map(DriveItemInfo::itemId)
                .toList();
        } catch (GraphAccessException ex) {
            LOG.warn("[sync] job={} cannot list scenario folder '{}': {}", jobId, folder.name(), ex.getMessage());
            syncEventService.emit(jobId, "folder.failed", payload(
                "folder", text(folder.name()),
                "error", text(ex.getMessage())));
            return List.of();
        }
    }

    private boolean isWorkbook(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(XLSX_SUFFIX);
    }

    // ------------------------------------------------------------------
    // Per-file pipeline
    // ------------------------------------------------------------------

    /**
     * Result of prefetching one file's Graph metadata + content — pure data, no side effects
     * (no SSE emission, no counts, no DB writes). {@code metadataError}/{@code fetchError} carry
     * exactly the exception {@link #processFile} used to catch inline, so downstream handling is
     * unchanged; only when the network calls happen moves earlier (concurrently), not what happens.
     */
    private record PrefetchResult(
        String itemId,
        DriveItemDetails details,
        GraphAccessException metadataError,
        String scenarioFolder,
        String s3Key,
        FetchOutcome outcome,
        Exception fetchError
    ) {
        boolean metadataFailed() {
            return metadataError != null;
        }

        boolean fetchFailed() {
            return fetchError != null;
        }
    }

    /**
     * Fetches every file's Graph metadata + content concurrently (the dominant latency cost),
     * preserving {@code itemIds} order in the returned list so the caller can process results
     * sequentially exactly as if the loop had been fully serial.
     */
    private List<PrefetchResult> prefetchAll(Cohort cohort, String driveId, List<String> itemIds) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PrefetchResult>> futures = itemIds.stream()
                .map(itemId -> executor.submit(() -> prefetch(cohort, driveId, itemId)))
                .toList();
            List<PrefetchResult> results = new ArrayList<>(futures.size());
            for (Future<PrefetchResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while prefetching score sheets", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Unexpected error prefetching score sheets", ex.getCause());
        }
    }

    private PrefetchResult prefetch(Cohort cohort, String driveId, String itemId) {
        // B3 AC1 — single-item GET for quickXorHash, content version and size, before any download.
        DriveItemDetails details;
        try {
            details = graphDriveService.getItem(driveId, itemId);
        } catch (GraphAccessException ex) {
            return new PrefetchResult(itemId, null, ex, null, null, null, null);
        }

        String scenarioFolder = resolveScenarioFolder(details.parentFolderName());
        String s3Key = buildS3Key(cohort.getId(), scenarioFolder, text(details.name()));

        try {
            FetchOutcome outcome = workbookFetchService.fetchIfChanged(driveId, itemId, details, s3Key);
            return new PrefetchResult(itemId, details, null, scenarioFolder, s3Key, outcome, null);
        } catch (GraphAccessException | WorkbookParseException | S3StorageException ex) {
            return new PrefetchResult(itemId, details, null, scenarioFolder, s3Key, null, ex);
        }
    }

    private void processFile(Cohort cohort, UUID jobId, PrefetchResult prefetched, SyncCounts counts,
                             UUID actorId, String triggerType) {
        String itemId = prefetched.itemId();
        if (prefetched.metadataFailed()) {
            // No filename or key is known yet, so there is nothing to key an audit row on.
            LOG.warn("[sync] job={} cannot read metadata for item {}: {}",
                jobId, itemId, prefetched.metadataError().getMessage());
            syncEventService.emit(jobId, "file.failed", payload(
                "itemId", itemId,
                "error", text(prefetched.metadataError().getMessage())));
            counts.failed++;
            return;
        }

        DriveItemDetails details = prefetched.details();
        String fileName = text(details.name());
        String scenarioFolder = prefetched.scenarioFolder();
        String s3Key = prefetched.s3Key();

        syncEventService.emit(jobId, "file.discovered", payload(
            "file", fileName,
            "itemId", itemId,
            "versionId", text(details.versionId()),
            "quickXorHash", text(details.quickXorHash())));

        if (prefetched.fetchFailed()) {
            // Download, parse or archive-read failure — fail this workbook only (§4.5, B4 AC2).
            // The S3 baseline is left untouched, so the next run retries this file.
            LOG.warn("[sync] job={} file '{}' failed: {}", jobId, fileName, prefetched.fetchError().getMessage());
            saveFileRecord(cohort, jobId, itemId, fileName, scenarioFolder, s3Key,
                details, null, null, SyncFileChangeState.FAILED);
            syncEventService.emit(jobId, "file.failed", payload(
                "file", fileName,
                "error", text(prefetched.fetchError().getMessage())));
            counts.failed++;
            return;
        }

        FetchOutcome outcome = prefetched.outcome();
        if (!outcome.hasWorkbook()) {
            // B3 AC2 — unchanged: no parse, no upload, but still recorded so the run summary can
            // say "we saw it and nothing changed" rather than leaving silence.
            saveFileRecord(cohort, jobId, itemId, fileName, scenarioFolder, s3Key,
                details, outcome.sha256Hex(), null, SyncFileChangeState.UNCHANGED);
            // D1 AC2 / D4 AC2 — the hash short-circuit also gets its own ingestion_runs row
            // (status=skipped), so the audit-log API surfaces the dedup, not just cohort_sync_files.
            gradingIngestionService.recordSkipped(cohort, jobId, fileName, details,
                outcome.sha256Hex(), actorId, triggerType);
            syncEventService.emit(jobId, "file.unchanged", payload("file", fileName));
            counts.unchanged++;
            return;
        }

        processChangedFile(cohort, jobId, itemId, scenarioFolder, details, outcome, counts, actorId, triggerType);
    }

    /**
     * Handles a {@code NEW} or {@code CHANGED} workbook: hands it to the processing step, then
     * archives the bytes and records the outcome.
     */
    private void processChangedFile(Cohort cohort, UUID jobId, String itemId, String scenarioFolder,
                                    DriveItemDetails details, FetchOutcome outcome, SyncCounts counts,
                                    UUID actorId, String triggerType) {
        String fileName = text(details.name());

        try (FetchedWorkbook workbook = outcome.workbook()) {
            syncEventService.emit(jobId, "file.changed", payload(
                "file", fileName,
                "state", outcome.state().name(),
                "sheets", workbook.workbook().getNumberOfSheets()));

            // B5–B9: sheet selection, row validation, classification and upsert into lab_results.
            // Sequenced before the archive below on purpose — the archive is what marks this
            // version done, so a processing failure must leave the old baseline in place.
            try {
                IngestionRun ingestionRun = gradingIngestionService.process(cohort, jobId, fileName,
                    workbook.workbook(), details, outcome.sha256Hex(), actorId, triggerType);
                if (ingestionRun != null && ingestionRun.isHighFailureRate()) {
                    raiseHighFailureRateAlert(cohort, jobId, fileName, ingestionRun, actorId);
                }
            } catch (RuntimeException ex) {
                LOG.warn("[sync] job={} grading ingestion failed for '{}': {}",
                    jobId, fileName, ex.getMessage());
                saveFileRecord(cohort, jobId, itemId, fileName, scenarioFolder,
                    buildS3Key(cohort.getId(), scenarioFolder, fileName),
                    details, outcome.sha256Hex(), null, SyncFileChangeState.FAILED);
                syncEventService.emit(jobId, "file.ingestion_failed", payload(
                    "file", fileName,
                    "error", text(ex.getMessage())));
                counts.failed++;
                return;
            }

            String s3VersionId = s3StorageService.putObject(
                workbook.s3Key(), workbook.content(), XLSX_CONTENT_TYPE);

            saveFileRecord(cohort, jobId, itemId, fileName, scenarioFolder, workbook.s3Key(),
                details, outcome.sha256Hex(), s3VersionId, outcome.state());

            syncEventService.emit(jobId, "file.archived", payload(
                "file", fileName,
                "s3Key", workbook.s3Key(),
                "versionId", text(s3VersionId),
                "state", outcome.state().name()));

            countChanged(outcome.state(), counts);
        } catch (S3StorageException ex) {
            // Archive failed: the baseline has not moved, so the next run retries this file.
            LOG.warn("[sync] job={} could not archive '{}': {}", jobId, fileName, ex.getMessage());
            saveFileRecord(cohort, jobId, itemId, fileName, scenarioFolder,
                buildS3Key(cohort.getId(), scenarioFolder, fileName),
                details, outcome.sha256Hex(), null, SyncFileChangeState.FAILED);
            syncEventService.emit(jobId, "file.archive_failed", payload(
                "file", fileName,
                "error", text(ex.getMessage())));
            counts.failed++;
        } catch (IOException ex) {
            // Thrown only by Workbook.close(); the work itself already succeeded.
            LOG.warn("[sync] job={} failed to close workbook '{}': {}", jobId, fileName, ex.getMessage());
        }
    }

    /**
     * §4.5 — "No hard stop on failure rate": valid rows still commit, but a sheet where more than
     * half its READY rows were rejected gets a loud, immediate alert (B7 AC3) rather than silence.
     */
    private void raiseHighFailureRateAlert(Cohort cohort, UUID jobId, String fileName, IngestionRun run,
                                           UUID actorId) {
        LOG.warn("[sync] job={} file '{}' HIGH FAILURE RATE {}% (invalid={} of read={})",
            jobId, fileName, String.format(Locale.ROOT, "%.1f", run.getFailureRatePercent()),
            run.getSkippedInvalid(), run.getRowsRead());

        Map<String, Object> payload = payload(
            "file", fileName,
            "failureRatePercent", run.getFailureRatePercent(),
            "rowsRead", run.getRowsRead(),
            "skippedInvalid", run.getSkippedInvalid(),
            "committedNew", run.getCommittedNew(),
            "updatedCount", run.getUpdatedCount());
        syncEventService.emit(jobId, "file.high_failure_rate", payload);
        auditEventService.record("HIGH_FAILURE_RATE", cohort.getId(), actorId, payload);
    }

    private void countChanged(SyncFileChangeState state, SyncCounts counts) {
        if (state == SyncFileChangeState.NEW) {
            counts.newFiles++;
        } else {
            counts.changed++;
        }
    }

    // ------------------------------------------------------------------
    // Persistence & helpers
    // ------------------------------------------------------------------

    /**
     * Writes the per-file audit row. Called on every branch — changed, unchanged and failed — so
     * that a missing row means "the run never reached this file", never "nothing changed"
     * (PRD D1 AC2).
     */
    private void saveFileRecord(Cohort cohort, UUID jobId, String itemId, String fileName,
                                String scenarioFolder, String s3Key, DriveItemDetails details,
                                String sha256Hex, String s3VersionId, SyncFileChangeState state) {
        try {
            syncFileRepository.save(CohortSyncFile.builder()
                .cohort(cohort)
                .syncJob(syncJobRepository.getReferenceById(jobId))
                .s3Key(s3Key)
                .s3VersionId(s3VersionId)
                .fileName(fileName)
                .scenarioFolder(scenarioFolder)
                .sharepointItemId(itemId)
                .quickXorHash(details == null ? null : details.quickXorHash())
                .sharepointVersionId(details == null ? null : details.versionId())
                .fileSha256(sha256Hex)
                .changeState(state)
                .build());
        } catch (RuntimeException ex) {
            // Losing the audit row must not lose the file's actual outcome from the run.
            LOG.error("[sync] job={} could not record sync file row for '{}': {}",
                jobId, fileName, ex.getMessage());
        }
    }

    private String buildS3Key(UUID cohortId, String scenarioFolder, String fileName) {
        String prefix = "cohorts/" + cohortId + "/scores/";
        if (scenarioFolder != null && !scenarioFolder.isBlank()) {
            prefix += scenarioFolder + "/";
        }
        return prefix + fileName;
    }

    /** A subfolder is a "scenario folder" only when it isn't the scores folder itself. */
    private String resolveScenarioFolder(String parentFolderName) {
        if (parentFolderName == null || parentFolderName.equalsIgnoreCase(sharePointProperties.scoresFolder())) {
            return null;
        }
        return parentFolderName;
    }

    /** {@code Map.of} rejects nulls, and SSE payload values are frequently absent. */
    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Object> payload(Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            map.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return map;
    }

    /** Per-run tally surfaced in the run summary (B3 AC2) and the completion audit event. */
    private static final class SyncCounts {
        private int newFiles;
        private int changed;
        private int unchanged;
        private int failed;

        private int filesSeen() {
            return newFiles + changed + unchanged + failed;
        }

        private Map<String, Object> toPayload(String cohortName) {
            Map<String, Object> map = new LinkedHashMap<>();
            if (cohortName != null) {
                map.put("cohortName", cohortName);
            }
            map.put("filesSeen", filesSeen());
            map.put("new", newFiles);
            map.put("changed", changed);
            map.put("unchanged", unchanged);
            map.put("failed", failed);
            return map;
        }

        @Override
        public String toString() {
            return "filesSeen=" + filesSeen() + " new=" + newFiles + " changed=" + changed
                + " unchanged=" + unchanged + " failed=" + failed;
        }
    }
}
