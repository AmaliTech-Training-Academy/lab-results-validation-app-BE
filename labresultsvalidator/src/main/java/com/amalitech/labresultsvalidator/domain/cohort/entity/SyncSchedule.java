package com.amalitech.labresultsvalidator.domain.cohort.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "sync_schedules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncSchedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(length = 150)
    private String name;

    /** Cohort this schedule syncs; null means "every eligible cohort", same as the default batch. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cohort_id")
    private Cohort cohort;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleFrequency frequency;

    @Column(name = "time_of_day", nullable = false)
    private LocalTime timeOfDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 10)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;
}
