package com.amalitech.labresultsvalidator.domain.reference.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.reference.dto.CohortReferenceResponse;
import com.amalitech.labresultsvalidator.domain.reference.dto.SpecializationWithModulesResponse;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorSpecializationAssignment;
import com.amalitech.labresultsvalidator.domain.reference.entity.Lab;
import com.amalitech.labresultsvalidator.domain.reference.entity.LabModule;
import com.amalitech.labresultsvalidator.domain.reference.entity.Learner;
import com.amalitech.labresultsvalidator.domain.reference.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorSpecializationAssignmentRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabModuleRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.LearnerRepository;
import com.amalitech.labresultsvalidator.domain.reference.repository.SpecializationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CohortReferenceQueryServiceTest {

    @Mock
    private CohortRepository cohortRepository;

    @Mock
    private SpecializationRepository specializationRepository;

    @Mock
    private LabModuleRepository labModuleRepository;

    @Mock
    private LabRepository labRepository;

    @Mock
    private LearnerRepository learnerRepository;

    @Mock
    private InstructorContactRepository instructorContactRepository;

    @Mock
    private InstructorSpecializationAssignmentRepository instructorSpecializationAssignmentRepository;

    @InjectMocks
    private CohortReferenceQueryService cohortReferenceQueryService;

    private final UUID cohortId = UUID.randomUUID();
    private final UUID specializationId = UUID.randomUUID();
    private final UUID moduleId = UUID.randomUUID();

    @Test
    void getCohortReference_cohortNotFound_throwsResourceNotFoundException() {
        when(cohortRepository.existsById(cohortId)).thenReturn(false);

        assertThatThrownBy(() -> cohortReferenceQueryService.getCohortReference(cohortId))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(specializationRepository, never()).findAllByCohortId(any());
    }

    @Test
    void getCohortReference_assemblesNestedSpecializationsModulesAndLabs() {
        when(cohortRepository.existsById(cohortId)).thenReturn(true);

        Specialization specialization = Specialization.builder()
            .id(specializationId)
            .cohortId(cohortId)
            .name("Software Engineering")
            .code("SWE")
            .build();
        when(specializationRepository.findAllByCohortId(cohortId)).thenReturn(List.of(specialization));

        LabModule module = LabModule.builder()
            .id(moduleId)
            .specializationId(specializationId)
            .name("Backend Fundamentals")
            .code("BEM01")
            .status("active")
            .build();
        when(labModuleRepository.findAllBySpecializationIdIn(List.of(specializationId)))
            .thenReturn(List.of(module));

        Lab lab = Lab.builder()
            .id(UUID.randomUUID())
            .moduleId(moduleId)
            .title("REST API Basics")
            .maxScore(BigDecimal.valueOf(100))
            .build();
        when(labRepository.findAllByModuleIdIn(List.of(moduleId))).thenReturn(List.of(lab));

        Learner learner = Learner.builder()
            .id(UUID.randomUUID())
            .fullName("Ama Owusu")
            .email("ama.owusu@example.com")
            .cohortId(cohortId)
            .specializationId(specializationId)
            .status("active")
            .build();
        when(learnerRepository.findAllByCohortId(cohortId)).thenReturn(List.of(learner));

        InstructorContact instructor = InstructorContact.builder()
            .id(UUID.randomUUID())
            .email("kofi.instructor@example.com")
            .fullName("Kofi Mensah")
            .isActive(true)
            .build();
        InstructorSpecializationAssignment assignment = InstructorSpecializationAssignment.builder()
            .id(UUID.randomUUID())
            .instructorContactId(instructor.getId())
            .specializationId(specializationId)
            .build();
        when(instructorSpecializationAssignmentRepository.findAllBySpecializationIdIn(List.of(specializationId)))
            .thenReturn(List.of(assignment));
        when(instructorContactRepository.findAllById(List.of(instructor.getId())))
            .thenReturn(List.of(instructor));

        CohortReferenceResponse response = cohortReferenceQueryService.getCohortReference(cohortId);

        assertThat(response.getSpecializations()).hasSize(1);
        SpecializationWithModulesResponse specResponse = response.getSpecializations().get(0);
        assertThat(specResponse.getCode()).isEqualTo("SWE");
        assertThat(specResponse.getModules()).hasSize(1);
        assertThat(specResponse.getModules().get(0).getCode()).isEqualTo("BEM01");
        assertThat(specResponse.getModules().get(0).getLabs()).hasSize(1);
        assertThat(specResponse.getModules().get(0).getLabs().get(0).getTitle()).isEqualTo("REST API Basics");

        assertThat(response.getLearners()).hasSize(1);
        assertThat(response.getLearners().get(0).getEmail()).isEqualTo("ama.owusu@example.com");

        assertThat(response.getInstructors()).hasSize(1);
        assertThat(response.getInstructors().get(0).getEmail()).isEqualTo("kofi.instructor@example.com");
    }

    @Test
    void getCohortReference_noSpecializations_returnsEmptyListsWithoutQueryingModulesOrLabs() {
        when(cohortRepository.existsById(cohortId)).thenReturn(true);
        when(specializationRepository.findAllByCohortId(cohortId)).thenReturn(List.of());
        when(labModuleRepository.findAllBySpecializationIdIn(List.of())).thenReturn(List.of());
        when(labRepository.findAllByModuleIdIn(List.of())).thenReturn(List.of());
        when(learnerRepository.findAllByCohortId(cohortId)).thenReturn(List.of());
        when(instructorSpecializationAssignmentRepository.findAllBySpecializationIdIn(List.of()))
            .thenReturn(List.of());
        when(instructorContactRepository.findAllById(List.of())).thenReturn(List.of());

        CohortReferenceResponse response = cohortReferenceQueryService.getCohortReference(cohortId);

        assertThat(response.getSpecializations()).isEmpty();
        assertThat(response.getLearners()).isEmpty();
        assertThat(response.getInstructors()).isEmpty();
    }

    @Test
    void getCohortReference_doesNotLeakInstructorsFromOtherCohorts() {
        // Regression test: instructor_contacts is a global table (instructors teach across
        // cohorts), so this cohort's response must be scoped through
        // instructor_specialization_assignments rather than listing every instructor in the
        // system — otherwise an instructor who only teaches a different cohort would show up here.
        when(cohortRepository.existsById(cohortId)).thenReturn(true);

        Specialization specialization = Specialization.builder()
            .id(specializationId)
            .cohortId(cohortId)
            .name("Software Engineering")
            .code("SWE")
            .build();
        when(specializationRepository.findAllByCohortId(cohortId)).thenReturn(List.of(specialization));
        when(labModuleRepository.findAllBySpecializationIdIn(List.of(specializationId))).thenReturn(List.of());
        when(labRepository.findAllByModuleIdIn(List.of())).thenReturn(List.of());
        when(learnerRepository.findAllByCohortId(cohortId)).thenReturn(List.of());

        // No assignment links this cohort's specialization to any instructor.
        when(instructorSpecializationAssignmentRepository.findAllBySpecializationIdIn(List.of(specializationId)))
            .thenReturn(List.of());
        when(instructorContactRepository.findAllById(List.of())).thenReturn(List.of());

        CohortReferenceResponse response = cohortReferenceQueryService.getCohortReference(cohortId);

        assertThat(response.getInstructors()).isEmpty();
        verify(instructorContactRepository, never()).findAll();
    }
}
