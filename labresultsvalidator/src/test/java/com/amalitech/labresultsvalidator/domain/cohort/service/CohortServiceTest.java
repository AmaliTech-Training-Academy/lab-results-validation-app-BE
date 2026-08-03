package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.AttachSharePointLinkRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CreateCohortRequest;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CohortServiceTest {

    @Mock
    private CohortRepository cohortRepository;

    @Mock
    private AuditEventService auditEventService;

    @InjectMocks
    private CohortService cohortService;

    private final User actor = User.builder()
        .id(UUID.randomUUID())
        .email("admin@test.com")
        .passwordHash("hashed")
        .role(UserRole.ADMIN)
        .isActive(true)
        .build();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CreateCohortRequest validRequest() {
        return CreateCohortRequest.builder()
            .name("Cohort 2026")
            .startDate(LocalDate.of(2026, 1, 1))
            .endDate(LocalDate.of(2026, 12, 31))
            .build();
    }

    @Test
    void createCohort_withValidRequest_createsInDraftStateAndActive() {
        when(cohortRepository.existsByNameIgnoreCase("Cohort 2026")).thenReturn(false);
        when(cohortRepository.save(any(Cohort.class))).thenAnswer(inv -> inv.getArgument(0));

        CohortResponse response = cohortService.createCohort(validRequest());

        assertThat(response.getLifecycleState()).isEqualTo(CohortLifecycleState.DRAFT);
        assertThat(response.isActive()).isTrue();
        assertThat(response.getName()).isEqualTo("Cohort 2026");

        ArgumentCaptor<Cohort> captor = ArgumentCaptor.forClass(Cohort.class);
        verify(cohortRepository).save(captor.capture());
        Cohort saved = captor.getValue();
        assertThat(saved.getCreatedBy()).isEqualTo(actor.getId());
        assertThat(saved.getUpdatedBy()).isEqualTo(actor.getId());
    }

    @Test
    void createCohort_withDuplicateName_throwsDuplicateResourceException() {
        when(cohortRepository.existsByNameIgnoreCase("Cohort 2026")).thenReturn(true);

        assertThatThrownBy(() -> cohortService.createCohort(validRequest()))
            .isInstanceOf(DuplicateResourceException.class)
            .hasMessage("Cohort name must be unique");

        verify(cohortRepository, never()).save(any());
    }

    @Test
    void getCohorts_returnsPagedResponse() {
        Cohort cohort = Cohort.builder()
            .id(UUID.randomUUID())
            .name("Cohort 2026")
            .startDate(LocalDate.of(2026, 1, 1))
            .endDate(LocalDate.of(2026, 12, 31))
            .lifecycleState(CohortLifecycleState.DRAFT)
            .isActive(true)
            .build();
        Page<Cohort> page = new PageImpl<>(List.of(cohort));
        when(cohortRepository.findAll(any(Pageable.class))).thenReturn(page);

        var result = cohortService.getCohorts(Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getName()).isEqualTo("Cohort 2026");
    }

    @Test
    void attachSharePointLink_toDraftCohort_setsUrl() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = Cohort.builder()
            .id(cohortId)
            .name("Cohort 2026")
            .lifecycleState(CohortLifecycleState.DRAFT)
            .isActive(true)
            .build();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));
        when(cohortRepository.save(any(Cohort.class))).thenAnswer(inv -> inv.getArgument(0));

        CohortResponse response = cohortService.attachSharePointLink(
            cohortId, AttachSharePointLinkRequest.builder().folderUrl("https://sharepoint/x").build());

        assertThat(response.getSharepointFolderUrl()).isEqualTo("https://sharepoint/x");
        verify(auditEventService).record(eq("LINK_SUBMITTED"), eq(cohortId), eq(actor.getId()), any());
    }

    @Test
    void attachSharePointLink_cohortNotFound_throwsResourceNotFoundException() {
        UUID cohortId = UUID.randomUUID();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cohortService.attachSharePointLink(
                cohortId, AttachSharePointLinkRequest.builder().folderUrl("https://sharepoint/x").build()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void attachSharePointLink_cohortNotDraft_throwsUnprocessableEntityException() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = Cohort.builder()
            .id(cohortId)
            .name("Cohort 2026")
            .lifecycleState(CohortLifecycleState.STOOD_UP)
            .isActive(true)
            .build();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));

        assertThatThrownBy(() -> cohortService.attachSharePointLink(
                cohortId, AttachSharePointLinkRequest.builder().folderUrl("https://sharepoint/x").build()))
            .isInstanceOf(UnprocessableEntityException.class);

        verify(cohortRepository, never()).save(any());
    }

    @Test
    void lockCohort_stoodUpCohort_setsLockedTrue() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = Cohort.builder()
            .id(cohortId)
            .name("Cohort 2026")
            .lifecycleState(CohortLifecycleState.STOOD_UP)
            .isLocked(false)
            .isActive(true)
            .build();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));
        when(cohortRepository.save(any(Cohort.class))).thenAnswer(inv -> inv.getArgument(0));

        CohortResponse response = cohortService.lockCohort(cohortId);

        assertThat(response.isLocked()).isTrue();
        verify(auditEventService).record(eq("COHORT_LOCKED"), eq(cohortId), eq(actor.getId()), any());
    }

    @Test
    void lockCohort_notStoodUp_throwsUnprocessableEntityException() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = Cohort.builder()
            .id(cohortId)
            .name("Cohort 2026")
            .lifecycleState(CohortLifecycleState.REFERENCE_ACCEPTED)
            .isLocked(false)
            .isActive(true)
            .build();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));

        assertThatThrownBy(() -> cohortService.lockCohort(cohortId))
            .isInstanceOf(UnprocessableEntityException.class);

        verify(cohortRepository, never()).save(any());
    }

    @Test
    void lockCohort_alreadyLocked_throwsUnprocessableEntityException() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = Cohort.builder()
            .id(cohortId)
            .name("Cohort 2026")
            .lifecycleState(CohortLifecycleState.STOOD_UP)
            .isLocked(true)
            .isActive(true)
            .build();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));

        assertThatThrownBy(() -> cohortService.lockCohort(cohortId))
            .isInstanceOf(UnprocessableEntityException.class);

        verify(cohortRepository, never()).save(any());
    }

    @Test
    void lockCohort_cohortNotFound_throwsResourceNotFoundException() {
        UUID cohortId = UUID.randomUUID();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cohortService.lockCohort(cohortId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unlockCohort_lockedCohort_setsLockedFalse() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = Cohort.builder()
            .id(cohortId)
            .name("Cohort 2026")
            .lifecycleState(CohortLifecycleState.STOOD_UP)
            .isLocked(true)
            .isActive(true)
            .build();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));
        when(cohortRepository.save(any(Cohort.class))).thenAnswer(inv -> inv.getArgument(0));

        CohortResponse response = cohortService.unlockCohort(cohortId);

        assertThat(response.isLocked()).isFalse();
        verify(auditEventService).record(eq("COHORT_UNLOCKED"), eq(cohortId), eq(actor.getId()), any());
    }

    @Test
    void unlockCohort_notLocked_throwsUnprocessableEntityException() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = Cohort.builder()
            .id(cohortId)
            .name("Cohort 2026")
            .lifecycleState(CohortLifecycleState.STOOD_UP)
            .isLocked(false)
            .isActive(true)
            .build();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));

        assertThatThrownBy(() -> cohortService.unlockCohort(cohortId))
            .isInstanceOf(UnprocessableEntityException.class);

        verify(cohortRepository, never()).save(any());
    }

    @Test
    void unlockCohort_cohortNotFound_throwsResourceNotFoundException() {
        UUID cohortId = UUID.randomUUID();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cohortService.unlockCohort(cohortId))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
