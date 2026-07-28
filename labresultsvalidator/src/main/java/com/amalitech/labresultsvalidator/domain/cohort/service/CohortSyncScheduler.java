package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.domain.cohort.dto.SyncBatchResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CohortSyncScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CohortSyncScheduler.class);

    private final CohortSyncService cohortSyncService;

    // Provisional default (Decision Log Q2): Monday 08:00. Zone is config-driven since the
    // GMT-vs-Europe/London DST question is parked (§8) — resolving either just changes config,
    // not code. actorId is null: a scheduled run is system-triggered, not attributable to a user.
    @Scheduled(
        cron = "${sync.schedule.cron:0 0 8 ? * MON}",
        zone = "${sync.schedule.zone:GMT}"
    )
    public void runScheduledSync() {
        LOG.info("[sync] scheduled sync run starting");
        SyncBatchResponse result = cohortSyncService.triggerScheduledSyncForAll();
        LOG.info("[sync] scheduled sync run dispatched: {} triggered, {} skipped",
            result.triggered(), result.skipped());
    }
}