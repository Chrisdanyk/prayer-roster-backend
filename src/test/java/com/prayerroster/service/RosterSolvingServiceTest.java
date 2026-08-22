package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.domain.Roster;
import com.prayerroster.domain.RosterGeneration;
import com.prayerroster.domain.RosterGenerationStatus;
import com.prayerroster.domain.RosterStatus;
import com.prayerroster.domain.User;
import com.prayerroster.repository.RosterGenerationRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.repository.UserAvailabilityRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.scheduling.RosterSolution;
import com.prayerroster.scheduling.SolverService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RosterSolvingServiceTest {

    @Mock
    private RosterRepository rosterRepository;

    @Mock
    private RosterGenerationRepository rosterGenerationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAvailabilityRepository userAvailabilityRepository;

    @Mock
    private SolverService solverService;

    @Mock
    private NotificationService notificationService;

    private RosterSolvingService service;

    @BeforeEach
    void setUp() {
        service = new RosterSolvingService(
            rosterRepository,
            rosterGenerationRepository,
            userRepository,
            userAvailabilityRepository,
            solverService,
            notificationService
        );
        when(userAvailabilityRepository.findActiveOverlapping(any(), any())).thenReturn(List.of());
    }

    private static User testUser(String id) {
        User user = new User();
        user.setId(id);
        user.setActive(true);
        user.setCanModerate(true);
        user.setCanPreach(true);
        return user;
    }

    private static PrayerAssignment assignment(Long id, User initialUser) {
        PrayerAssignment assignment = new PrayerAssignment();
        assignment.setId(id);
        assignment.setRole(PrayerAssignmentRole.MODERATOR);
        assignment.setUser(initialUser);
        PrayerSession session = new PrayerSession();
        session.setId(id);
        session.setDate(LocalDate.of(2026, 9, 6));
        assignment.setSession(session);
        return assignment;
    }

    private static Roster roster() {
        Roster roster = new Roster();
        roster.setId(1L);
        roster.setPeriodFrom(LocalDate.of(2026, 9, 1));
        roster.setPeriodTo(LocalDate.of(2026, 9, 30));
        roster.setStatus(RosterStatus.DRAFT);
        return roster;
    }

    private static RosterGeneration generation() {
        RosterGeneration generation = new RosterGeneration();
        generation.setId(9L);
        return generation;
    }

    /**
     * Real Timefold returns a clone of each planning entity from {@code getFinalBestSolution()} -
     * decoupled from the originals passed into the solve, which is exactly what lets {@code
     * RosterSolvingService} safely read an assignment's pre-solve {@code user} before overwriting
     * it. Mutating the original objects directly (instead of returning clones, as a naive mock
     * might) would corrupt that "previous vs. new" comparison - this helper avoids that trap.
     */
    private static PrayerAssignment solvedClone(PrayerAssignment original, User solvedUser) {
        PrayerAssignment clone = new PrayerAssignment();
        clone.setId(original.getId());
        clone.setRole(original.getRole());
        clone.setSession(original.getSession());
        clone.setUser(solvedUser);
        return clone;
    }

    @Test
    void solveAndApply_publishesRosterAndFillsAssignmentsWhenFeasible() {
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1")));
        PrayerAssignment managed = assignment(1L, null);
        RosterGeneration generation = generation();
        Roster roster = roster();

        when(solverService.solve(eq(generation.getId()), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, testUser("u1")))
            );
            solved.setScore(HardSoftScore.of(0, -3));
            return solved;
        });

        boolean feasible = service.solveAndApply(
            roster,
            generation,
            List.of(managed),
            roster.getPeriodFrom(),
            roster.getPeriodTo(),
            RosterStatus.DRAFT
        );

        assertThat(feasible).isTrue();
        assertThat(managed.getUser()).isNotNull();
        assertThat(roster.getStatus()).isEqualTo(RosterStatus.PUBLISHED);
        assertThat(roster.getPublishedAt()).isNotNull();
        assertThat(generation.getStatus()).isEqualTo(RosterGenerationStatus.COMPLETED);
        assertThat(generation.getHardScore()).isZero();
        assertThat(generation.getSoftScore()).isEqualTo(-3);
        assertThat(generation.getFeasible()).isTrue();
        verify(rosterRepository).save(roster);
        verify(rosterGenerationRepository).save(generation);
    }

    @Test
    void solveAndApply_notifiesOnlyTheNewlyAssignedUserWhenSlotWasPreviouslyEmpty() {
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1")));
        PrayerAssignment managed = assignment(1L, null);
        RosterGeneration generation = generation();
        Roster roster = roster();

        when(solverService.solve(eq(generation.getId()), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, testUser("u1")))
            );
            solved.setScore(HardSoftScore.of(0, 0));
            return solved;
        });

        service.solveAndApply(roster, generation, List.of(managed), roster.getPeriodFrom(), roster.getPeriodTo(), RosterStatus.DRAFT);

        verify(notificationService).notifyAssignmentPublished(managed);
        verify(notificationService, never()).notifyAssignmentRemoved(any(), any());
    }

    @Test
    void solveAndApply_notifiesBothUsersWhenReassignedToADifferentUser() {
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1"), testUser("u2")));
        User previousUser = testUser("u1");
        PrayerAssignment managed = assignment(1L, previousUser);
        RosterGeneration generation = generation();
        Roster roster = roster();

        when(solverService.solve(eq(generation.getId()), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, testUser("u2")))
            );
            solved.setScore(HardSoftScore.of(0, 0));
            return solved;
        });

        service.solveAndApply(roster, generation, List.of(managed), roster.getPeriodFrom(), roster.getPeriodTo(), RosterStatus.DRAFT);

        verify(notificationService).notifyAssignmentRemoved(eq(previousUser), eq(managed));
        verify(notificationService).notifyAssignmentPublished(managed);
        assertThat(managed.getUser().getId()).isEqualTo("u2");
    }

    @Test
    void solveAndApply_doesNotNotifyWhenAssignmentIsUnchanged() {
        User sameUser = testUser("u1");
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(sameUser));
        PrayerAssignment managed = assignment(1L, sameUser);
        RosterGeneration generation = generation();
        Roster roster = roster();

        when(solverService.solve(eq(generation.getId()), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, sameUser))
            );
            solved.setScore(HardSoftScore.of(0, 0));
            return solved;
        });

        service.solveAndApply(roster, generation, List.of(managed), roster.getPeriodFrom(), roster.getPeriodTo(), RosterStatus.DRAFT);

        verifyNoInteractions(notificationService);
    }

    @Test
    void solveAndApply_fallsBackToGivenStatusAndRecordsDiagnosticWhenInfeasible() {
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1")));
        RosterGeneration generation = generation();
        Roster roster = roster();
        roster.setStatus(RosterStatus.REQUIRES_RESCHEDULING);

        when(solverService.solve(eq(generation.getId()), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            problem.setScore(HardSoftScore.of(-2, 0));
            return problem;
        });
        when(solverService.explainHardConstraintViolations(any())).thenReturn("everyAssignmentMustBeFilled: 2 violation(s)");

        boolean feasible = service.solveAndApply(
            roster,
            generation,
            List.of(assignment(1L, null)),
            roster.getPeriodFrom(),
            roster.getPeriodTo(),
            RosterStatus.REQUIRES_RESCHEDULING
        );

        assertThat(feasible).isFalse();
        assertThat(roster.getStatus()).isEqualTo(RosterStatus.REQUIRES_RESCHEDULING);
        assertThat(generation.getStatus()).isEqualTo(RosterGenerationStatus.INFEASIBLE);
        assertThat(generation.getFeasible()).isFalse();
        assertThat(generation.getErrorMessage()).isEqualTo("everyAssignmentMustBeFilled: 2 violation(s)");
        verifyNoInteractions(notificationService);
    }

    @Test
    void solveAndApply_skipsSolvingWhenNoEligibleUserExists() {
        when(userRepository.findAllEligibleActive()).thenReturn(List.of());
        RosterGeneration generation = generation();
        Roster roster = roster();

        boolean feasible = service.solveAndApply(
            roster,
            generation,
            List.of(assignment(1L, null), assignment(2L, null)),
            roster.getPeriodFrom(),
            roster.getPeriodTo(),
            RosterStatus.DRAFT
        );

        assertThat(feasible).isFalse();
        assertThat(generation.getHardScore()).isEqualTo(-2);
        assertThat(generation.getSolverDurationMs()).isZero();
        assertThat(generation.getErrorMessage()).contains("No active user has any capability");
        verify(solverService, never()).solve(any(), any());
        verifyNoInteractions(notificationService);
    }
}
