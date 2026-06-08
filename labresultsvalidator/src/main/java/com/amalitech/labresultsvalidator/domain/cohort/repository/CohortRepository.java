package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CohortRepository extends JpaRepository<Cohort, UUID> {
    boolean existsByName(String name);
    Page<Cohort> findAll(Pageable pageable);
    @Query("""
        SELECT COUNT(m) > 0
        FROM Module m
        WHERE m.specialization.cohort.id = :cohortId
        AND m.specialization.cohort.active = true
        """)
    boolean hasActiveModules(@Param("cohortId") UUID cohortId);
}
