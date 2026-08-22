package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.Roster;
import com.prayerroster.domain.RosterGenerationTrigger;
import com.prayerroster.domain.RosterStatus;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.service.dto.GenerateRosterRequest;
import com.prayerroster.service.dto.RosterDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RollingRosterGenerationServiceTest {

    @Mock
    private RosterRepository rosterRepository;

    @Mock
    private RosterGenerationService rosterGenerationService;

    private RollingRosterGenerationService service;

    @BeforeEach
    void setUp() {
        service = new RollingRosterGenerationService(rosterRepository, rosterGenerationService);
    }

    private static Roster roster(LocalDate periodTo) {
        Roster roster = new Roster();
        roster.setPeriodTo(periodTo);
        return roster;
    }

    @Test
    void generateRollingWindow_startsFromTodayWhenNoRosterExistsYet() {
        when(rosterRepository.findTopByOrderByPeriodToDesc()).thenReturn(Optional.empty());
        when(rosterGenerationService.generate(any(), eq(RosterGenerationTrigger.SCHEDULED_CRON))).thenReturn(
            new RosterDTO(1L, LocalDate.now(), LocalDate.now().plusMonths(2), RosterStatus.PUBLISHED, null)
        );

        service.generateRollingWindow();

        ArgumentCaptor<GenerateRosterRequest> captor = ArgumentCaptor.forClass(GenerateRosterRequest.class);
        verify(rosterGenerationService).generate(captor.capture(), eq(RosterGenerationTrigger.SCHEDULED_CRON));
        assertThat(captor.getValue().from()).isEqualTo(LocalDate.now());
        assertThat(captor.getValue().to()).isEqualTo(LocalDate.now().plusMonths(RollingRosterGenerationService.HORIZON_MONTHS));
    }

    @Test
    void generateRollingWindow_startsTheDayAfterTheFurthestExistingRoster() {
        LocalDate existingPeriodTo = LocalDate.now().plusDays(10);
        when(rosterRepository.findTopByOrderByPeriodToDesc()).thenReturn(Optional.of(roster(existingPeriodTo)));
        when(rosterGenerationService.generate(any(), eq(RosterGenerationTrigger.SCHEDULED_CRON))).thenReturn(
            new RosterDTO(1L, existingPeriodTo.plusDays(1), LocalDate.now().plusMonths(2), RosterStatus.PUBLISHED, null)
        );

        service.generateRollingWindow();

        ArgumentCaptor<GenerateRosterRequest> captor = ArgumentCaptor.forClass(GenerateRosterRequest.class);
        verify(rosterGenerationService).generate(captor.capture(), eq(RosterGenerationTrigger.SCHEDULED_CRON));
        assertThat(captor.getValue().from()).isEqualTo(existingPeriodTo.plusDays(1));
    }

    @Test
    void generateRollingWindow_doesNothingWhenAlreadyCoveredPastTheHorizon() {
        LocalDate farFuture = LocalDate.now().plusMonths(RollingRosterGenerationService.HORIZON_MONTHS).plusDays(30);
        when(rosterRepository.findTopByOrderByPeriodToDesc()).thenReturn(Optional.of(roster(farFuture)));

        service.generateRollingWindow();

        verify(rosterGenerationService, never()).generate(any(), any());
    }

    @Test
    void generateRollingWindow_neverPropagatesAFailureFromGeneration() {
        when(rosterRepository.findTopByOrderByPeriodToDesc()).thenReturn(Optional.empty());
        when(rosterGenerationService.generate(any(), eq(RosterGenerationTrigger.SCHEDULED_CRON))).thenThrow(
            new BadRequestAlertException("no configuration", "roster", "noConfigurationForDate")
        );

        service.generateRollingWindow();
    }
}
