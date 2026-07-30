package com.amalitech.labresultsvalidator.config;

import jakarta.annotation.PostConstruct;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Applies Apache POI's zip-bomb guards once at startup (risk R-10: we parse untrusted
 * {@code .xlsx} bytes in-process).
 *
 * <p>{@link ZipSecureFile}'s limits are JVM-global statics, so they belong here rather than
 * being re-set inside a per-file parse loop — where one caller's choice silently becomes
 * every caller's.
 *
 * <p>The inflate ratio is configurable because a legitimate, highly repetitive sheet can trip
 * POI's stricter default. The default here is well below POI's 0.01 but still bounds expansion
 * at 2000:1, and {@code maxEntryBytes} caps any single decompressed entry regardless — so a
 * permissive ratio never means an unbounded read.
 */
@Configuration
public class PoiHardeningConfig {

    private static final Logger LOG = LoggerFactory.getLogger(PoiHardeningConfig.class);

    private final double minInflateRatio;
    private final long maxEntryBytes;

    public PoiHardeningConfig(
        @Value("${labgate.poi.min-inflate-ratio}") double minInflateRatio,
        @Value("${labgate.poi.max-entry-bytes}") long maxEntryBytes
    ) {
        this.minInflateRatio = minInflateRatio;
        this.maxEntryBytes = maxEntryBytes;
    }

    @PostConstruct
    void applyLimits() {
        ZipSecureFile.setMinInflateRatio(minInflateRatio);
        ZipSecureFile.setMaxEntrySize(maxEntryBytes);
        LOG.info("POI hardening applied — minInflateRatio={} maxEntrySize={} bytes",
            minInflateRatio, maxEntryBytes);
    }
}
