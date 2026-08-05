package com.amalitech.labresultsvalidator.domain.grading.sync;

import com.amalitech.labresultsvalidator.domain.sync.entity.SyncFileChangeState;
import com.amalitech.labresultsvalidator.infrastructure.storage.S3StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Decides whether a score sheet changed since the last run (B3).
 *
 * <p>The comparand is the S3 archive itself, not a stored fingerprint. Each file is always
 * written to the same key in a versioned bucket, so the object currently at that key <em>is</em>
 * the copy the previous run archived. That means detection needs no database read at all, and
 * there is nothing to seed on a first run: an absent key simply means the file is new.
 *
 * <p>Uploading a changed file (which the caller does after processing) creates a new version
 * and thereby advances the baseline. Withholding that upload is how a failed file gets retried
 * next run.
 */
@Component
public class ScoreSheetChangeDetector {

    private static final Logger LOG = LoggerFactory.getLogger(ScoreSheetChangeDetector.class);

    private final S3StorageService s3StorageService;

    public ScoreSheetChangeDetector(S3StorageService s3StorageService) {
        this.s3StorageService = s3StorageService;
    }

    /**
     * Compares freshly downloaded bytes against the archived copy.
     *
     * <p>An S3 read failure is allowed to propagate rather than being treated as "changed":
     * guessing would re-upload and re-notify on every throttled read, and §4.5 wants the
     * smallest self-contained unit — this one file — to fail instead.
     *
     * @param s3Key      the file's stable archive key
     * @param freshBytes bytes just downloaded from SharePoint
     * @return {@code NEW}, {@code UNCHANGED} or {@code CHANGED}
     */
    public SyncFileChangeState detect(String s3Key, byte[] freshBytes) {
        if (!s3StorageService.exists(s3Key)) {
            LOG.debug("[change] {} — no archived copy, treating as NEW", s3Key);
            return SyncFileChangeState.NEW;
        }

        byte[] archived = s3StorageService.getObject(s3Key);
        if (Arrays.equals(freshBytes, archived)) {
            LOG.debug("[change] {} — identical to archived copy ({} bytes), UNCHANGED", s3Key, archived.length);
            return SyncFileChangeState.UNCHANGED;
        }

        LOG.debug("[change] {} — differs from archived copy ({} -> {} bytes), CHANGED",
            s3Key, archived.length, freshBytes.length);
        return SyncFileChangeState.CHANGED;
    }
}
