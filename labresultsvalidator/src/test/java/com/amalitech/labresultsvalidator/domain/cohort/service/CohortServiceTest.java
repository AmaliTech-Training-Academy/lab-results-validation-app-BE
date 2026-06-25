package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CreateCohortRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.UpdateCohortRequest;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CohortServiceTest {

    @Mock
    private CohortRepository cohortRepository;

    @InjectMocks
    private CohortService cohortService;

    private User currentUser;
    private CreateCohortRequest request;

    @BeforeEach
    void setUp() {
        currentUser = User.builder()
                .id(UUID.randomUUID())
                .email("admin@test.com")
                .passwordHash("hash")
                .role(UserRole.ADMIN)
                .build();

        request = new CreateCohortRequest();
        setField(request, "name", "Cohort 12");
        setField(request, "startDate", LocalDate.of(2025, 1, 1));
        setField(request, "endDate", LocalDate.of(2025, 6, 30));

        SecurityContextImpl context = new SecurityContextImpl();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, null, currentUser.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── createCohort ─────────────────────────────────────────────────────────

    @Test
    void createCohort_withValidRequest_returnsPopulatedResponse() {
        Cohort saved = buildCohort("Cohort 12");
        when(cohortRepository.existsByName("Cohort 12")).thenReturn(false);
        when(cohortRepository.save(any())).thenReturn(saved);

        CohortResponse response = cohortService.createCohort(request);

        assertThat(response.getName()).isEqualTo("Cohort 12");
        assertThat(response.getStartDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(response.getEndDate()).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(response.isActive()).isTrue();
        assertThat(response.getId()).isNotNull();
    }

    @Test
    void createCohort_setsCreatedByAndUpdatedByFromCurrentUser() {
        when(cohortRepository.existsByName(any())).thenReturn(false);
        when(cohortRepository.save(any())).thenReturn(buildCohort("Cohort 12"));

        cohortService.createCohort(request);

        ArgumentCaptor<Cohort> captor = ArgumentCaptor.forClass(Cohort.class);
        verify(cohortRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(currentUser.getId());
        assertThat(captor.getValue().getUpdatedBy()).isEqualTo(currentUser.getId());
    }

    @Test
    void createCohort_setsActiveTrueByDefault() {
        when(cohortRepository.existsByName(any())).thenReturn(false);
        when(cohortRepository.save(any())).thenReturn(buildCohort("Cohort 12"));

        cohortService.createCohort(request);

        ArgumentCaptor<Cohort> captor = ArgumentCaptor.forClass(Cohort.class);
        verify(cohortRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    void createCohort_setsLockedTrueByDefault() {
        when(cohortRepository.existsByName(any())).thenReturn(false);
        when(cohortRepository.save(any())).thenReturn(buildCohort("Cohort 12"));

        cohortService.createCohort(request);

        ArgumentCaptor<Cohort> captor = ArgumentCaptor.forClass(Cohort.class);
        verify(cohortRepository).save(captor.capture());
        assertThat(captor.getValue().isLocked()).isTrue();
    }

    @Test
    void createCohort_whenNameAlreadyExists_throwsDuplicateResourceException() {
        when(cohortRepository.existsByName("Cohort 12")).thenReturn(true);

        assertThatThrownBy(() -> cohortService.createCohort(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Cohort 12");

        verify(cohortRepository, never()).save(any());
    }

    @Test
    void createCohort_whenEndDateEqualsStartDate_delegatesToSave() {
        setField(request, "endDate", LocalDate.of(2025, 1, 1));
        Cohort saved = buildCohort("Cohort 12");
        when(cohortRepository.existsByName(any())).thenReturn(false);
        when(cohortRepository.save(any())).thenReturn(saved);

        // Date-order validation is enforced at the DTO layer (@EndDateAfterStartDate);
        // the service itself does not re-check it for create — it trusts validated input.
        cohortService.createCohort(request);

        verify(cohortRepository).save(any());
    }

    @Test
    void createCohort_whenEndDateBeforeStartDate_delegatesToSave() {
        setField(request, "endDate", LocalDate.of(2024, 12, 31));
        Cohort saved = buildCohort("Cohort 12");
        when(cohortRepository.existsByName(any())).thenReturn(false);
        when(cohortRepository.save(any())).thenReturn(saved);

        // Date-order validation is enforced at the DTO layer (@EndDateAfterStartDate);
        // the service itself does not re-check it for create — it trusts validated input.
        cohortService.createCohort(request);

        verify(cohortRepository).save(any());
    }

    // ── getCohorts ────────────────────────────────────────────────────────────

    @Test
    void getCohorts_returnsPagedResponse() {
        Cohort cohort = buildCohort("Cohort 12");
        Pageable pageable = PageRequest.of(0, 10);
        when(cohortRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(cohort), pageable, 1));

        PagedResponse<CohortResponse> response = cohortService.getCohorts(pageable);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getName()).isEqualTo("Cohort 12");
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getTotalPages()).isEqualTo(1);
        assertThat(response.isLast()).isTrue();
    }

    @Test
    void getCohorts_whenNoCohorts_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 10);
        when(cohortRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PagedResponse<CohortResponse> response = cohortService.getCohorts(pageable);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
    }

    // ── updateCohort ──────────────────────────────────────────────────────────

    @Test
    void updateCohort_whenCohortIsLocked_throwsUnprocessableEntityException() {
        Cohort locked = buildLockedCohort("Cohort 12");
        when(cohortRepository.findById(locked.getId())).thenReturn(Optional.of(locked));

        UpdateCohortRequest updateRequest = new UpdateCohortRequest();
        setField(updateRequest, "name", "Cohort 13");

        assertThatThrownBy(() -> cohortService.updateCohort(locked.getId(), updateRequest))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("locked")
                .hasMessageContaining("Cohort 12");

        verify(cohortRepository, never()).save(any());
    }

    @Test
    void updateCohort_whenCohortIsUnlocked_updatesAndPersists() {
        Cohort cohort = buildCohort("Cohort 12");
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.of(cohort));
        when(cohortRepository.existsByNameAndIdNot("Cohort 13", cohort.getId())).thenReturn(false);
        when(cohortRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateCohortRequest updateRequest = new UpdateCohortRequest();
        setField(updateRequest, "name", "Cohort 13");

        CohortResponse response = cohortService.updateCohort(cohort.getId(), updateRequest);

        assertThat(response.getName()).isEqualTo("Cohort 13");
        verify(cohortRepository).save(any());
    }

    // ── deleteCohort ──────────────────────────────────────────────────────────

    @Test
    void deleteCohort_withValidId_deletesCohort() {
        Cohort cohort = buildCohort("Cohort 12");
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.of(cohort));
        when(cohortRepository.hasActiveModules(cohort.getId())).thenReturn(false);

        cohortService.deleteCohort(cohort.getId());

        verify(cohortRepository).delete(cohort);
    }

    @Test
    void deleteCohort_whenCohortNotFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(cohortRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cohortService.deleteCohort(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());

        verify(cohortRepository, never()).delete(any());
    }

    @Test
    void deleteCohort_whenCohortHasActiveModules_throwsUnprocessableEntityException() {
        Cohort cohort = buildCohort("Cohort 12");
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.of(cohort));
        when(cohortRepository.hasActiveModules(cohort.getId())).thenReturn(true);

        assertThatThrownBy(() -> cohortService.deleteCohort(cohort.getId()))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Cohort 12");

        verify(cohortRepository, never()).delete(any());
    }

    // ── lockCohort ────────────────────────────────────────────────────────────

    @Test
    void lockCohort_whenCohortNotFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(cohortRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cohortService.lockCohort(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());

        verify(cohortRepository, never()).save(any());
    }

    @Test
    void lockCohort_whenAlreadyLocked_throwsUnprocessableEntityException() {
        Cohort locked = buildLockedCohort("Cohort 12");
        when(cohortRepository.findById(locked.getId())).thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> cohortService.lockCohort(locked.getId()))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("already locked");

        verify(cohortRepository, never()).save(any());
    }

    @Test
    void lockCohort_whenUnlocked_setsLockedTrueAndPersists() {
        Cohort cohort = buildCohort("Cohort 12");
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.of(cohort));
        when(cohortRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CohortResponse response = cohortService.lockCohort(cohort.getId());

        ArgumentCaptor<Cohort> captor = ArgumentCaptor.forClass(Cohort.class);
        verify(cohortRepository).save(captor.capture());
        assertThat(captor.getValue().isLocked()).isTrue();
        assertThat(response.isLocked()).isTrue();
    }

    // ── unlockCohort ──────────────────────────────────────────────────────────

    @Test
    void unlockCohort_whenCohortNotFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(cohortRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cohortService.unlockCohort(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());

        verify(cohortRepository, never()).save(any());
    }

    @Test
    void unlockCohort_whenAlreadyUnlocked_throwsUnprocessableEntityException() {
        Cohort cohort = buildCohort("Cohort 12");
        when(cohortRepository.findById(cohort.getId())).thenReturn(Optional.of(cohort));

        assertThatThrownBy(() -> cohortService.unlockCohort(cohort.getId()))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("already unlocked");

        verify(cohortRepository, never()).save(any());
    }

    @Test
    void unlockCohort_whenLocked_setsLockedFalseAndPersists() {
        Cohort locked = buildLockedCohort("Cohort 12");
        when(cohortRepository.findById(locked.getId())).thenReturn(Optional.of(locked));
        when(cohortRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CohortResponse response = cohortService.unlockCohort(locked.getId());

        ArgumentCaptor<Cohort> captor = ArgumentCaptor.forClass(Cohort.class);
        verify(cohortRepository).save(captor.capture());
        assertThat(captor.getValue().isLocked()).isFalse();
        assertThat(response.isLocked()).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Cohort buildCohort(String name) {
        Cohort cohort = Cohort.builder()
                .id(UUID.randomUUID())
                .name(name)
                .startDate(LocalDate.of(2025, 1, 1))
                .endDate(LocalDate.of(2025, 6, 30))
                .active(true)
                .locked(false)
                .build();
        cohort.setCreatedBy(currentUser.getId());
        cohort.setUpdatedBy(currentUser.getId());
        return cohort;
    }

    private Cohort buildLockedCohort(String name) {
        Cohort cohort = Cohort.builder()
                .id(UUID.randomUUID())
                .name(name)
                .startDate(LocalDate.of(2025, 1, 1))
                .endDate(LocalDate.of(2025, 6, 30))
                .active(true)
                .locked(true)
                .build();
        cohort.setCreatedBy(currentUser.getId());
        cohort.setUpdatedBy(currentUser.getId());
        return cohort;
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