package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.domain.Roster;
import com.prayerroster.domain.RosterGeneration;
import com.prayerroster.domain.RosterGenerationTrigger;
import com.prayerroster.domain.RosterStatus;
import com.prayerroster.repository.PrayerSessionRepository;
import com.prayerroster.repository.RosterGenerationRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.service.dto.RosterDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReschedulingServiceTest {

    @Mock
    private RosterRepository rosterRepository;

    @Mock
    private RosterGenerationRepository rosterGenerationRepository;

    @Mock
    private PrayerSessionRepository prayerSessionRepository;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private ReschedulingService service;

    @BeforeEach
    void setUp() {
        service = new ReschedulingService(rosterRepository, rosterGenerationRepository, prayerSessionRepository, eventPublisher);
    }

    private static Roster roster(RosterStatus status) {
        Roster roster = new Roster();
        roster.setId(1L);
        roster.setPeriodFrom(LocalDate.of(2026, 9, 1));
        roster.setPeriodTo(LocalDate.of(2026, 9, 30));
        roster.setStatus(status);
        return roster;
    }

    private static PrayerSession session(Long id, boolean requiresRescheduling) {
        PrayerSession session = new PrayerSession();
        session.setId(id);
        session.setDate(LocalDate.of(2026, 9, id.intValue()));
        session.setRequiresRescheduling(requiresRescheduling);
        PrayerAssignment assignment = new PrayerAssignment();
        assignment.setId(id);
        assignment.setRole(PrayerAssignmentRole.MODERATOR);
        assignment.setSession(session);
        session.getAssignments().add(assignment);
        return session;
    }

    private void stubGenerationSave() {
        AtomicLong ids = new AtomicLong(1);
        when(rosterGenerationRepository.save(any(RosterGeneration.class))).thenAnswer(inv -> {
            RosterGeneration g = inv.getArgument(0);
            g.setId(ids.getAndIncrement());
            return g;
        });
    }

    @Test
    void reschedule_throwsWhenRosterNotFound() {
        when(rosterRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reschedule(1L, null)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void reschedule_rejectsWhenRosterDoesNotRequireRescheduling() {
        when(rosterRepository.findById(1L)).thenReturn(Optional.of(roster(RosterStatus.PUBLISHED)));

        assertThatThrownBy(() -> service.reschedule(1L, null)).isInstanceOf(BadRequestAlertException.class);
        verify(prayerSessionRepository, never()).findByRosterIdWithAssignments(any());
    }

    @Test
    void reschedule_rejectsWhenNoSessionIsFlagged() {
        Roster roster = roster(RosterStatus.REQUIRES_RESCHEDULING);
        when(rosterRepository.findById(1L)).thenReturn(Optional.of(roster));
        when(prayerSessionRepository.findByRosterIdWithAssignments(1L)).thenReturn(List.of(session(1L, false), session(2L, false)));

        assertThatThrownBy(() -> service.reschedule(1L, null)).isInstanceOf(BadRequestAlertException.class);
        verify(rosterGenerationRepository, never()).save(any());
    }

    @Test
    void reschedule_pinsUnflaggedAssignmentsAndPublishesTheSolveRequest() {
        stubGenerationSave();
        Roster roster = roster(RosterStatus.REQUIRES_RESCHEDULING);
        PrayerSession flagged = session(1L, true);
        PrayerSession unflagged = session(2L, false);
        when(rosterRepository.findById(1L)).thenReturn(Optional.of(roster));
        when(prayerSessionRepository.findByRosterIdWithAssignments(1L)).thenReturn(List.of(flagged, unflagged));

        RosterDTO result = service.reschedule(1L, "Indisponibilité");

        assertThat(result.id()).isEqualTo(1L);
        PrayerAssignment flaggedAssignment = flagged.getAssignments().iterator().next();
        PrayerAssignment unflaggedAssignment = unflagged.getAssignments().iterator().next();
        assertThat(flaggedAssignment.isLocked()).isFalse();
        assertThat(unflaggedAssignment.isLocked()).isTrue();

        ArgumentCaptor<RosterGeneration> captor = ArgumentCaptor.forClass(RosterGeneration.class);
        verify(rosterGenerationRepository).save(captor.capture());
        RosterGeneration generation = captor.getValue();
        assertThat(generation.getTrigger()).isEqualTo(RosterGenerationTrigger.RESCHEDULE);
        assertThat(generation.isRegenerated()).isTrue();
        assertThat(generation.getRescheduleReason()).isEqualTo("Indisponibilité");
        assertThat(flaggedAssignment.getGeneration()).isEqualTo(generation);
        assertThat(unflaggedAssignment.getGeneration()).isEqualTo(generation);

        ArgumentCaptor<RosterGenerationRequestedEvent> eventCaptor = ArgumentCaptor.forClass(RosterGenerationRequestedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().generationId()).isEqualTo(generation.getId());
    }
}
