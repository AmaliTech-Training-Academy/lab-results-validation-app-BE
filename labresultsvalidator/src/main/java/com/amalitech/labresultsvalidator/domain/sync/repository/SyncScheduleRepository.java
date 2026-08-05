package com.amalitech.labresultsvalidator.domain.sync.repository;

import com.amalitech.labresultsvalidator.domain.sync.entity.SyncSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SyncScheduleRepository extends JpaRepository<SyncSchedule, UUID> {

    List<SyncSchedule> findByEnabledTrue();
}
