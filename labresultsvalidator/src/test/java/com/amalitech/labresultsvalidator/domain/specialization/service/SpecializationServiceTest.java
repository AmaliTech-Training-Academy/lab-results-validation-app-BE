package com.amalitech.labresultsvalidator.domain.specialization.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.specialization.dto.CreateSpecializationRequest;
import com.amalitech.labresultsvalidator.domain.specialization.dto.SpecializationResponse;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.specialization.repository.SpecializationRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpecializationServiceTest {

    @Mock
    private SpecializationRepository specializationRepository;

    @Mock
    private CohortRepository cohortRepository;

    @InjectMocks
    private SpecializationService specializationService;

    private User currentUser;
    private Cohort cohort;
    private CreateSpecializationRequest request;

    @BeforeEach
    void setUp() {
        currentUser = User.builder()
                .id(UUID.randomUUID())
                .email("admin@test.com")
                .passwordHash("hash")
                .role(UserRole.ADMIN)
                .build();

        cohort = Cohort.builder()
                .id(UUID.randomUUID())
                .name("Cohort 12")
                .startDate(LocalDate.of(2025, 1, 1))
                .endDate(LocalDate.of(2025, 6, 30))
                .active(true)
                .build();

        request = new CreateSpecializationRequest();
        setField(request, "cohortId", cohort.getId());
        setField(request, "name", "Software Engineering");
        setField(request, "code", "swe");

        SecurityContextImpl context = new SecurityContextImpl();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, null, currentUser.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createSpecialization_withValidRequest_returnsPopulatedResponse() {
        Specialization saved = buildSavedSpecialization("SWE");
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.of(cohort));
        when(specializationRepository.existsByCohortIdAndName(any(), any())).thenReturn(false);
        when(specializationRepository.existsByCohortIdAndCode(any(), any())).thenReturn(false);
        when(specializationRepository.save(any())).thenReturn(saved);

        SpecializationResponse response = specializationService.createSpecialization(request);

        assertThat(response.getName()).isEqualTo("Software Engineering");
        assertThat(response.getCode()).isEqualTo("SWE");
        assertThat(response.getCohortId()).isEqualTo(cohort.getId());
        assertThat(response.getId()).isNotNull();
    }

    @Test
    void createSpecialization_savesCodeAsUppercase() {
        Specialization saved = buildSavedSpecialization("SWE");
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.of(cohort));
        when(specializationRepository.existsByCohortIdAndName(any(), any())).thenReturn(false);
        when(specializationRepository.existsByCohortIdAndCode(any(), any())).thenReturn(false);
        when(specializationRepository.save(any())).thenReturn(saved);

        specializationService.createSpecialization(request);

        ArgumentCaptor<Specialization> captor = ArgumentCaptor.forClass(Specialization.class);
        verify(specializationRepository).save(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("SWE");
    }

    @Test
    void createSpecialization_setsCreatedByAndUpdatedByFromCurrentUser() {
        Specialization saved = buildSavedSpecialization("SWE");
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.of(cohort));
        when(specializationRepository.existsByCohortIdAndName(any(), any())).thenReturn(false);
        when(specializationRepository.existsByCohortIdAndCode(any(), any())).thenReturn(false);
        when(specializationRepository.save(any())).thenReturn(saved);

        specializationService.createSpecialization(request);

        ArgumentCaptor<Specialization> captor = ArgumentCaptor.forClass(Specialization.class);
        verify(specializationRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(currentUser.getId());
        assertThat(captor.getValue().getUpdatedBy()).isEqualTo(currentUser.getId());
    }

    @Test
    void createSpecialization_whenCohortNotFound_throwsResourceNotFoundException() {
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> specializationService.createSpecialization(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(cohort.getId().toString());

        verify(specializationRepository, never()).save(any());
    }

    @Test
    void createSpecialization_whenNameExistsInCohort_throwsDuplicateResourceException() {
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.of(cohort));
        when(specializationRepository.existsByCohortIdAndName(cohort.getId(), "Software Engineering"))
                .thenReturn(true);

        assertThatThrownBy(() -> specializationService.createSpecialization(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Software Engineering");

        verify(specializationRepository, never()).save(any());
    }

    @Test
    void createSpecialization_whenCodeExistsInCohort_throwsDuplicateResourceException() {
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.of(cohort));
        when(specializationRepository.existsByCohortIdAndName(any(), any())).thenReturn(false);
        when(specializationRepository.existsByCohortIdAndCode(cohort.getId(), "swe")).thenReturn(true);

        assertThatThrownBy(() -> specializationService.createSpecialization(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("swe");

        verify(specializationRepository, never()).save(any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Specialization buildSavedSpecialization(String code) {
        Specialization s = Specialization.builder()
                .id(UUID.randomUUID())
                .cohort(cohort)
                .name("Software Engineering")
                .code(code)
                .build();
        s.setCreatedBy(currentUser.getId());
        s.setUpdatedBy(currentUser.getId());
        return s;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}