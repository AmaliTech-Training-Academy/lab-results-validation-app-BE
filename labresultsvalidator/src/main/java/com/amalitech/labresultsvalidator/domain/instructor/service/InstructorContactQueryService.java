package com.amalitech.labresultsvalidator.domain.instructor.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.instructor.dto.InstructorContactResponse;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstructorContactQueryService {

    private final InstructorContactRepository instructorContactRepository;

    public Page<InstructorContactResponse> listAll(Pageable pageable) {
        return instructorContactRepository.findAll(pageable).map(this::toResponse);
    }

    public InstructorContactResponse getById(UUID id) {
        return instructorContactRepository.findById(id)
            .map(this::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Instructor not found with id: " + id));
    }

    private InstructorContactResponse toResponse(InstructorContact instructor) {
        return InstructorContactResponse.builder()
            .id(instructor.getId())
            .instructorId(instructor.getInstructorId())
            .email(instructor.getEmail())
            .fullName(instructor.getFullName())
            .isActive(instructor.isActive())
            .build();
    }
}
