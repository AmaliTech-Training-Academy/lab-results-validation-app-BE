package com.amalitech.labresultsvalidator.domain.lab.repository;

import com.amalitech.labresultsvalidator.domain.lab.entity.Lab;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LabRepository extends JpaRepository<Lab, UUID> {

    boolean existsByModuleIdAndTitle(UUID moduleId, String title);

    @EntityGraph(attributePaths = {"module"})
    Page<Lab> findAllByModuleId(UUID moduleId, Pageable pageable);

    @EntityGraph(attributePaths = {"module"})
    Page<Lab> findAllByOrderByTitleAsc(Pageable pageable);
}
