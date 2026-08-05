package com.amalitech.labresultsvalidator.domain.grading.sync;

import com.amalitech.labresultsvalidator.common.utils.Sha256Util;
import com.amalitech.labresultsvalidator.domain.sync.entity.SyncFileChangeState;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemDetails;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * Fetches a score sheet and parses it locally when it has changed (B4).
 *
 * <p>Content is downloaded via Graph and opened with Apache POI in-process — the Graph Excel
 * workbook API is never called (B4 AC1, PRD decision D-READ). Parsing locally is what gives
 * exact version-to-data correspondence: the bytes hashed are the bytes POI read.
 */
@Service
public class WorkbookFetchService {

    private static final Logger LOG = LoggerFactory.getLogger(WorkbookFetchService.class);

    private final GraphDriveService graphDriveService;
    private final ScoreSheetChangeDetector changeDetector;
    private final SharePointProperties sharePointProperties;

    public WorkbookFetchService(
        GraphDriveService graphDriveService,
        ScoreSheetChangeDetector changeDetector,
        SharePointProperties sharePointProperties
    ) {
        this.graphDriveService = graphDriveService;
        this.changeDetector = changeDetector;
        this.sharePointProperties = sharePointProperties;
    }

    /**
     * Downloads a workbook, decides whether it changed, and opens it if so.
     *
     * @param driveId the drive holding the file
     * @param itemId  the file's drive item id
     * @param details metadata from the single-item GET (B3 AC1)
     * @param s3Key   the file's stable archive key
     * @return the outcome; it carries an open workbook for {@code NEW}/{@code CHANGED}, and
     *         none for {@code UNCHANGED} — in which case POI is never invoked (B3 AC2)
     * @throws GraphAccessException   the file is oversized, or the download failed after retries
     * @throws WorkbookParseException POI could not open the bytes (B4 AC2)
     */
    public FetchOutcome fetchIfChanged(
        String driveId,
        String itemId,
        DriveItemDetails details,
        String s3Key
    ) throws GraphAccessException, WorkbookParseException {

        rejectIfOversized(details);

        byte[] bytes = graphDriveService.downloadFile(driveId, itemId);

        // Hashed over the exact bytes that POI is about to read, so the recorded fingerprint
        // always describes the data actually processed (B4 AC3).
        String sha256Hex = Sha256Util.sha256Hex(bytes);

        SyncFileChangeState state = changeDetector.detect(s3Key, bytes);
        if (state == SyncFileChangeState.UNCHANGED) {
            LOG.info("[fetch] {} unchanged — skipping parse", details.name());
            return FetchOutcome.unchanged(sha256Hex);
        }

        Workbook workbook = open(details.name(), bytes);
        LOG.info("[fetch] {} {} — parsed {} sheet(s), {} bytes",
            details.name(), state, workbook.getNumberOfSheets(), bytes.length);

        return new FetchOutcome(state, sha256Hex, new FetchedWorkbook(
            details.name(),
            itemId,
            s3Key,
            bytes,
            workbook
        ));
    }

    /**
     * Rejects an oversized workbook before the download rather than after, using the size Graph
     * reported in the metadata GET. A missing size is not treated as a failure — the bounded
     * read in {@code downloadFile} still caps what can enter the heap.
     */
    private void rejectIfOversized(DriveItemDetails details) throws GraphAccessException {
        Long size = details.sizeBytes();
        long cap = sharePointProperties.maxWorkbookBytes();
        if (size != null && size > cap) {
            throw new GraphAccessException("Workbook '" + details.name() + "' is " + size
                + " bytes, above the " + cap + " byte limit — not downloaded.");
        }
    }

    /**
     * Opens the bytes with POI. Catches {@link Exception} rather than {@code IOException} alone
     * because POI signals malformed OOXML through a family of unchecked exceptions
     * ({@code NotOfficeXmlFileException}, {@code POIXMLException}, {@code RecordFormatException}),
     * and any of them must fail only this workbook (B4 AC2).
     */
    private Workbook open(String fileName, byte[] bytes) throws WorkbookParseException {
        try {
            return WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (Exception ex) {
            LOG.warn("[fetch] POI could not open '{}': {}", fileName, ex.getMessage());
            throw new WorkbookParseException(
                "Could not open workbook '" + fileName + "' — it may be corrupt or not a valid "
                    + ".xlsx file (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ").",
                ex);
        }
    }
}
