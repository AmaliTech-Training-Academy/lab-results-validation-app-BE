package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.StandUpJobResponse;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandUpJob;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandUpJobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortStandUpJobRepository;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CohortStandUpServiceTest {

    @Mock
    private CohortRepository cohortRepository;

    @Mock
    private CohortStandUpJobRepository standUpJobRepository;

    @InjectMocks
    private CohortStandUpService cohortStandUpService;

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

    private Cohort draftCohortWithLink(UUID id) {
        return Cohort.builder()
            .id(id)
            .name("Cohort 2026")
            .lifecycleState(CohortLifecycleState.DRAFT)
            .isActive(true)
            .sharepointFolderUrl("https://sharepoint/x")
            .build();
    }

    @Test
    void startStandUp_withDraftCohortAndLink_createsRunningJob() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = draftCohortWithLink(cohortId);
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));
        when(standUpJobRepository.existsByCohortIdAndStatus(cohortId, CohortStandUpJobStatus.RUNNING))
            .thenReturn(false);
        when(standUpJobRepository.save(any(CohortStandUpJob.class))).thenAnswer(inv -> {
            CohortStandUpJob job = inv.getArgument(0);
            job.setId(UUID.randomUUID());
            return job;
        });

        StandUpJobResponse response = cohortStandUpService.startStandUp(cohortId);

        assertThat(response.getStatus()).isEqualTo(CohortStandUpJobStatus.RUNNING);
        assertThat(response.getCohortId()).isEqualTo(cohortId);
    }

    @Test
    void startStandUp_cohortNotFound_throwsResourceNotFoundException() {
        UUID cohortId = UUID.randomUUID();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cohortStandUpService.startStandUp(cohortId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void startStandUp_cohortNotDraft_throwsUnprocessableEntityException() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = draftCohortWithLink(cohortId);
        cohort.setLifecycleState(CohortLifecycleState.STOOD_UP);
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));

        assertThatThrownBy(() -> cohortStandUpService.startStandUp(cohortId))
            .isInstanceOf(UnprocessableEntityException.class);

        verify(standUpJobRepository, never()).save(any());
    }

    @Test
    void startStandUp_withoutSharePointLink_throwsUnprocessableEntityException() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = draftCohortWithLink(cohortId);
        cohort.setSharepointFolderUrl(null);
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));

        assertThatThrownBy(() -> cohortStandUpService.startStandUp(cohortId))
            .isInstanceOf(UnprocessableEntityException.class);

        verify(standUpJobRepository, never()).save(any());
    }

    @Test
    void startStandUp_withJobAlreadyRunning_throwsDuplicateResourceException() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = draftCohortWithLink(cohortId);
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));
        when(standUpJobRepository.existsByCohortIdAndStatus(cohortId, CohortStandUpJobStatus.RUNNING))
            .thenReturn(true);

        assertThatThrownBy(() -> cohortStandUpService.startStandUp(cohortId))
            .isInstanceOf(DuplicateResourceException.class)
            .hasMessage("A stand-up job is already running for this cohort");

        verify(standUpJobRepository, never()).save(any());
    }

    @Test
    void startStandUp_raceOnInsert_translatesToDuplicateResourceException() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = draftCohortWithLink(cohortId);
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));
        when(standUpJobRepository.existsByCohortIdAndStatus(cohortId, CohortStandUpJobStatus.RUNNING))
            .thenReturn(false);
        when(standUpJobRepository.save(any(CohortStandUpJob.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> cohortStandUpService.startStandUp(cohortId))
            .isInstanceOf(DuplicateResourceException.class)
            .hasMessage("A stand-up job is already running for this cohort");
    }
}
