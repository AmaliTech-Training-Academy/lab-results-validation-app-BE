package com.amalitech.labresultsvalidator.domain.lab.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.lab.dto.CreateLabRequest;
import com.amalitech.labresultsvalidator.domain.lab.dto.LabResponse;
import com.amalitech.labresultsvalidator.domain.lab.dto.PatchLabRequest;
import com.amalitech.labresultsvalidator.domain.lab.entity.Lab;
import com.amalitech.labresultsvalidator.domain.lab.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.module.entity.Module;
import com.amalitech.labresultsvalidator.domain.module.repository.ModuleRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class LabService {

    private final LabRepository labRepository;
    private final ModuleRepository moduleRepository;

    public LabResponse createLab(CreateLabRequest request) {
        User currentUser = (User) SecurityContextHolder
                .getContext().getAuthentication()
                .getPrincipal();

        Module module = moduleRepository.findById(request.getModuleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Module with id '" + request.getModuleId() + "' not found"));

        boolean locked = moduleRepository.findCohortIsLockedById(request.getModuleId()).orElse(false);
        if (locked) {
            throw new UnprocessableEntityException("Cohort is locked and cannot be modified");
        }

        if (labRepository.existsByModuleIdAndTitle(request.getModuleId(), request.getTitle())) {
            throw new DuplicateResourceException(
                    "Lab with title '" + request.getTitle() + "' already exists in this module");
        }

        Lab lab = Lab.builder()
                .module(module)
                .title(request.getTitle())
                .maxScore(request.getMaxScore())
                .build();
        lab.setCreatedBy(currentUser.getId());
        lab.setUpdatedBy(currentUser.getId());

        return mapToResponse(labRepository.save(lab));
    }

    public LabResponse patchLab(UUID id, PatchLabRequest request) {
        User currentUser = (User) SecurityContextHolder
                .getContext().getAuthentication()
                .getPrincipal();

        Lab lab = labRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lab with id '" + id + "' not found"));

        boolean locked = moduleRepository.findCohortIsLockedById(lab.getModule().getId()).orElse(false);
        if (locked) {
            throw new UnprocessableEntityException("Cohort is locked and cannot be modified");
        }

        if (lab.isImmutable()) {
            throw new UnprocessableEntityException("Lab is immutable and cannot be modified");
        }

        if (request.getTitle() != null && !request.getTitle().equals(lab.getTitle())) {
            if (labRepository.existsByModuleIdAndTitle(lab.getModule().getId(), request.getTitle())) {
                throw new DuplicateResourceException(
                        "Lab with title '" + request.getTitle() + "' already exists in this module");
            }
            lab.setTitle(request.getTitle());
        }

        if (request.getMaxScore() != null) {
            lab.setMaxScore(request.getMaxScore());
        }

        if (request.getImmutable() != null) {
            lab.setImmutable(request.getImmutable());
        }

        lab.setUpdatedBy(currentUser.getId());

        return mapToResponse(labRepository.save(lab));
    }

    @Transactional(readOnly = true)
    public Page<LabResponse> listLabs(UUID moduleId, Pageable pageable) {
        if (moduleId != null) {
            return labRepository.findAllByModuleId(moduleId, pageable).map(this::mapToResponse);
        }
        return labRepository.findAllByOrderByTitleAsc(pageable).map(this::mapToResponse);
    }

    private LabResponse mapToResponse(Lab lab) {
        return LabResponse.builder()
                .id(lab.getId())
                .moduleId(lab.getModule().getId())
                .moduleName(lab.getModule().getName())
                .title(lab.getTitle())
                .maxScore(lab.getMaxScore())
                .immutable(lab.isImmutable())
                .createdAt(lab.getCreatedAt())
                .updatedAt(lab.getUpdatedAt())
                .build();
    }
}
