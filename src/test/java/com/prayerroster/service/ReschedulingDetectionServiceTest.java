package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.domain.Roster;
import com.prayerroster.domain.RosterStatus;
import com.prayerroster.repository.PrayerAssignmentRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class ReschedulingDetectionServiceTest {

    @Mock
    private PrayerAssignmentRepository prayerAssignmentRepository;

    @Mock
    private ReschedulingService reschedulingService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private ReschedulingDetectionService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new ReschedulingDetectionService(prayerAssignmentRepository, reschedulingService, transactionManager);
    }

    private static PrayerAssignment assignmentOn(Roster roster, LocalDate date) {
        PrayerSession session = new PrayerSession();
        session.setDate(date);
        session.setRoster(roster);
        PrayerAssignment assignment = new PrayerAssignment();
        assignment.setRole(PrayerAssignmentRole.MODERATOR);
        assignment.setSession(session);
        return assignment;
    }

    private static Roster roster(Long id) {
        Roster roster = new Roster();
        roster.setId(id);
        roster.setStatus(RosterStatus.PUBLISHED);
        return roster;
    }

    @Test
    void onAvailabilityCreated_doesNothingWhenNoAssignmentIsAffected() {
        when(prayerAssignmentRepository.findPublishedAssignmentsForUserInRange(eq("u1"), any(), any())).thenReturn(List.of());

        service.onAvailabilityCreated("u1", LocalDate.now().plusDays(1), LocalDate.now().plusDays(5));

        verify(reschedulingService, never()).reschedule(any(), any());
    }

    @Test
    void onAvailabilityCreated_clampsStartDateToTodayWhenPartlyInThePast() {
        LocalDate start = LocalDate.now().minusDays(3);
        LocalDate end = LocalDate.now().plusDays(3);
        when(prayerAssignmentRepository.findPublishedAssignmentsForUserInRange("u1", LocalDate.now(), end)).thenReturn(List.of());

        service.onAvailabilityCreated("u1", start, end);

        verify(prayerAssignmentRepository).findPublishedAssignmentsForUserInRange("u1", LocalDate.now(), end);
    }

    @Test
    void onAvailabilityCreated_doesNothingWhenFullyInThePast() {
        LocalDate start = LocalDate.now().minusDays(10);
        LocalDate end = LocalDate.now().minusDays(1);

        service.onAvailabilityCreated("u1", start, end);

        verify(prayerAssignmentRepository, never()).findPublishedAssignmentsForUserInRange(any(), any(), any());
    }

    @Test
    void onAvailabilityCreated_flagsSessionAndRosterThenReschedules() {
        Roster roster = roster(5L);
        PrayerAssignment affected = assignmentOn(roster, LocalDate.now().plusDays(2));
        when(prayerAssignmentRepository.findPublishedAssignmentsForUserInRange(eq("u1"), any(), any())).thenReturn(List.of(affected));

        service.onAvailabilityCreated("u1", LocalDate.now().plusDays(1), LocalDate.now().plusDays(5));

        assertThat(affected.getSession().isRequiresRescheduling()).isTrue();
        assertThat(roster.getStatus()).isEqualTo(RosterStatus.REQUIRES_RESCHEDULING);
        verify(reschedulingService).reschedule(eq(5L), any());
    }

    @Test
    void onAvailabilityCreated_reschedulesEachDistinctAffectedRosterOnlyOnce() {
        Roster rosterA = roster(1L);
        Roster rosterB = roster(2L);
        PrayerAssignment first = assignmentOn(rosterA, LocalDate.now().plusDays(1));
        PrayerAssignment second = assignmentOn(rosterA, LocalDate.now().plusDays(2));
        PrayerAssignment third = assignmentOn(rosterB, LocalDate.now().plusDays(3));
        when(prayerAssignmentRepository.findPublishedAssignmentsForUserInRange(eq("u1"), any(), any())).thenReturn(
            List.of(first, second, third)
        );

        service.onUserDeactivated("u1");

        verify(reschedulingService).reschedule(eq(1L), any());
        verify(reschedulingService).reschedule(eq(2L), any());
    }

    @Test
    void onUserDeactivated_queriesFromTodayThroughFarFuture() {
        when(prayerAssignmentRepository.findPublishedAssignmentsForUserInRange("u1", LocalDate.now(), LocalDate.MAX)).thenReturn(List.of());

        service.onUserDeactivated("u1");

        verify(prayerAssignmentRepository).findPublishedAssignmentsForUserInRange("u1", LocalDate.now(), LocalDate.MAX);
    }

    @Test
    void detection_neverPropagatesAFailureFromReschedulingService() {
        Roster roster = roster(5L);
        PrayerAssignment affected = assignmentOn(roster, LocalDate.now().plusDays(2));
        when(prayerAssignmentRepository.findPublishedAssignmentsForUserInRange(eq("u1"), any(), any())).thenReturn(List.of(affected));
        when(reschedulingService.reschedule(any(), any())).thenThrow(new RuntimeException("solve blew up"));

        service.onAvailabilityCreated("u1", LocalDate.now().plusDays(1), LocalDate.now().plusDays(5));

        // No assertion needed beyond "did not throw" - the roster stays flagged for manual retry.
        assertThat(roster.getStatus()).isEqualTo(RosterStatus.REQUIRES_RESCHEDULING);
    }
}
