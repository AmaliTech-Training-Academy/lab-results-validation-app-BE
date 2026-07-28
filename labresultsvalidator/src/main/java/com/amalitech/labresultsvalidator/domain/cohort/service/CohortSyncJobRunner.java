package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortSyncJobRepository;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Fetches (enumerates + downloads) score sheet files from a cohort's SharePoint "Lab Scores"
 * folder. Validation of the fetched data is intentionally out of scope — this is the
 * discovery/fetch step a future validation ticket will build on. One instance of this runner
 * handles a single cohort's job in isolation: a failure here never affects a sibling cohort's
 * job from the same batch (B2 AC3).
 */
@Service
@RequiredArgsConstructor
public class CohortSyncJobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CohortSyncJobRunner.class);

    private final CohortRepository cohortRepository;
    private final CohortSyncJobRepository syncJobRepository;
    private final SyncEventService syncEventService;
    private final StandupSseRegistry sseRegistry;
    private final AuditEventService auditEventService;
    private final GraphDriveService graphDriveService;
    private final SharePointProperties sharePointProperties;

    @Async("syncTaskExecutor")
    public void run(UUID cohortId, UUID jobId, UUID actorId, String targetItemId) {
        LOG.info("[sync] job={} cohort={} STARTED targetItemId={}", jobId, cohortId, targetItemId);
        CohortSyncJobStatus finalStatus = CohortSyncJobStatus.FAILED;
        int fetchedCount = 0;

        try {
            Cohort cohort = cohortRepository.findById(cohortId)
                .orElseThrow(() -> new IllegalStateException("Cohort " + cohortId + " not found"));

            String driveId = cohort.getSharepointDriveId();
            String parentItemId = cohort.getSharepointItemId();
            if (driveId == null || parentItemId == null) {
                throw new IllegalStateException("Cohort is missing SharePoint drive reference.");
            }

            fetchedCount = targetItemId != null
                ? fetchSingleFile(driveId, targetItemId, jobId)
                : fetchScoresFolder(driveId, parentItemId, jobId);

            finalStatus = CohortSyncJobStatus.COMPLETED;
            auditEventService.record("SYNC_COMPLETED", cohortId, actorId, Map.of(
                "cohortName", cohort.getName(),
                "filesFetched", fetchedCount
            ));
            LOG.info("[sync] job={} cohort={} COMPLETED — {} file(s) fetched", jobId, cohortId, fetchedCount);
        } catch (Exception ex) {
            LOG.error("[sync] job={} cohort={} FAILED: {}", jobId, cohortId, ex.getMessage(), ex);
        }

        syncEventService.emit(jobId, "sync.done", Map.of("status", finalStatus.name()));
        sseRegistry.complete(jobId);

        CohortSyncJobStatus status = finalStatus;
        syncJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            job.setCompletedAt(OffsetDateTime.now());
            syncJobRepository.save(job);
        });
    }

    private int fetchSingleFile(String driveId, String targetItemId, UUID jobId) {
        syncEventService.emit(jobId, "file.discovered", Map.of("itemId", targetItemId));
        try {
            byte[] bytes = graphDriveService.downloadFile(driveId, targetItemId);
            syncEventService.emit(jobId, "file.fetched", Map.of("itemId", targetItemId, "bytes", bytes.length));
            return 1;
        } catch (GraphAccessException ex) {
            syncEventService.emit(jobId, "file.failed", Map.of("itemId", targetItemId, "error", ex.getMessage()));
            throw new IllegalStateException("Could not download file " + targetItemId + ": " + ex.getMessage(), ex);
        }
    }

    private int fetchScoresFolder(String driveId, String parentItemId, UUID jobId) {
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

        // Lab Scores may contain scenario subfolders or score sheets directly (production layout) —
        // same one-level traversal as Gate4ScoreSheetValidator.
        List<DriveItemInfo> xlsxFiles = new ArrayList<>();
        for (DriveItemInfo item : scoreFolderChildren) {
            if (item.isFolder()) {
                try {
                    List<DriveItemInfo> scenarioChildren = graphDriveService.listChildren(driveId, item.itemId());
                    for (DriveItemInfo child : scenarioChildren) {
                        if (!child.isFolder() && child.name() != null
                                && child.name().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                            xlsxFiles.add(child);
                        }
                    }
                } catch (GraphAccessException ex) {
                    throw new IllegalStateException(
                        "Cannot list scenario subfolder '" + item.name() + "': " + ex.getMessage(), ex);
                }
            } else if (item.name() != null && item.name().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                xlsxFiles.add(item);
            }
        }

        LOG.info("[sync] job={} discovered {} score sheet(s)", jobId, xlsxFiles.size());

        int fetched = 0;
        for (DriveItemInfo file : xlsxFiles) {
            syncEventService.emit(jobId, "file.discovered", Map.of("file", file.name(), "itemId", file.itemId()));
            try {
                byte[] bytes = graphDriveService.downloadFile(driveId, file.itemId());
                syncEventService.emit(jobId, "file.fetched", Map.of("file", file.name(), "bytes", bytes.length));
                fetched++;
            } catch (GraphAccessException ex) {
                syncEventService.emit(jobId, "file.failed", Map.of("file", file.name(), "error", ex.getMessage()));
            }
        }
        return fetched;
    }
}