package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import com.prayerroster.domain.RosterGenerationTrigger;
import com.prayerroster.domain.RosterStatus;
import com.prayerroster.domain.User;
import com.prayerroster.repository.RosterGenerationRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.repository.PrayerAssignmentRepository;
import com.prayerroster.repository.UserAvailabilityRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.scheduling.RosterSolution;
import com.prayerroster.scheduling.SolverService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
    private PrayerAssignmentRepository prayerAssignmentRepository;

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
            prayerAssignmentRepository,
            userRepository,
            userAvailabilityRepository,
            solverService,
            notificationService
        );
        lenient().when(userAvailabilityRepository.findActiveOverlapping(any(), any())).thenReturn(List.of());
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

    private static RosterGeneration generation(Roster roster, RosterGenerationTrigger trigger) {
        RosterGeneration generation = new RosterGeneration();
        generation.setId(9L);
        generation.setRoster(roster);
        generation.setTrigger(trigger);
        generation.setPlanningFrom(roster.getPeriodFrom());
        generation.setPlanningTo(roster.getPeriodTo());
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
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.MANUAL);
        PrayerAssignment managed = assignment(1L, null);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1")));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, testUser("u1")))
            );
            solved.setScore(HardSoftScore.of(0, -3));
            return solved;
        });

        boolean feasible = service.solveAndApply(9L);

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
        verify(userAvailabilityRepository).findActiveOverlapping(roster.getPeriodFrom(), roster.getPeriodTo());
    }

    @Test
    void solveAndApply_notifiesOnlyTheNewlyAssignedUserWhenSlotWasPreviouslyEmpty() {
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.MANUAL);
        PrayerAssignment managed = assignment(1L, null);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1")));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, testUser("u1")))
            );
            solved.setScore(HardSoftScore.of(0, 0));
            return solved;
        });

        service.solveAndApply(9L);

        verify(notificationService).notifyAssignmentsPublished(eq(testUser("u1")), eq(List.of(managed)));
        verify(notificationService, never()).notifyAssignmentsRemoved(any(), any());
    }

    @Test
    void solveAndApply_notifiesBothUsersWhenReassignedToADifferentUser() {
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.MANUAL);
        User previousUser = testUser("u1");
        PrayerAssignment managed = assignment(1L, previousUser);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(previousUser, testUser("u2")));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, testUser("u2")))
            );
            solved.setScore(HardSoftScore.of(0, 0));
            return solved;
        });

        service.solveAndApply(9L);

        verify(notificationService).notifyAssignmentsRemoved(eq(previousUser), eq(List.of(managed)));
        verify(notificationService).notifyAssignmentsPublished(eq(testUser("u2")), eq(List.of(managed)));
        assertThat(managed.getUser().getId()).isEqualTo("u2");
    }

    @Test
    void solveAndApply_doesNotNotifyWhenAssignmentIsUnchanged() {
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.MANUAL);
        User sameUser = testUser("u1");
        PrayerAssignment managed = assignment(1L, sameUser);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(sameUser));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, sameUser))
            );
            solved.setScore(HardSoftScore.of(0, 0));
            return solved;
        });

        service.solveAndApply(9L);

        verifyNoInteractions(notificationService);
    }

    @Test
    void solveAndApply_fallsBackToDraftAndRecordsDiagnosticWhenInfeasibleOnAFreshGeneration() {
        Roster roster = roster();
        roster.setStatus(RosterStatus.DRAFT);
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.MANUAL);
        PrayerAssignment managed = assignment(1L, null);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1")));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            problem.setScore(HardSoftScore.of(-2, 0));
            return problem;
        });
        when(solverService.explainHardConstraintViolations(any())).thenReturn("everyAssignmentMustBeFilled: 2 violation(s)");

        boolean feasible = service.solveAndApply(9L);

        assertThat(feasible).isFalse();
        assertThat(roster.getStatus()).isEqualTo(RosterStatus.DRAFT);
        assertThat(generation.getStatus()).isEqualTo(RosterGenerationStatus.INFEASIBLE);
        assertThat(generation.getFeasible()).isFalse();
        assertThat(generation.getErrorMessage()).isEqualTo("everyAssignmentMustBeFilled: 2 violation(s)");
        verifyNoInteractions(notificationService);
    }

    @Test
    void solveAndApply_fallsBackToRequiresReschedulingWhenInfeasibleOnAReschedule() {
        Roster roster = roster();
        roster.setStatus(RosterStatus.REQUIRES_RESCHEDULING);
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.RESCHEDULE);
        PrayerAssignment managed = assignment(1L, null);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1")));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            problem.setScore(HardSoftScore.of(-2, 0));
            return problem;
        });
        when(solverService.explainHardConstraintViolations(any())).thenReturn("everyAssignmentMustBeFilled: 2 violation(s)");

        boolean feasible = service.solveAndApply(9L);

        assertThat(feasible).isFalse();
        assertThat(roster.getStatus()).isEqualTo(RosterStatus.REQUIRES_RESCHEDULING);
    }

    @Test
    void solveAndApply_clearsRequiresReschedulingOnTheAffectedSessionWhenAFeasibleRescheduleSucceeds() {
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.RESCHEDULE);
        PrayerAssignment managed = assignment(1L, testUser("u1"));
        managed.getSession().setRequiresRescheduling(true);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1"), testUser("u2")));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, testUser("u2")))
            );
            solved.setScore(HardSoftScore.of(0, 0));
            return solved;
        });

        service.solveAndApply(9L);

        assertThat(managed.getSession().isRequiresRescheduling()).isFalse();
    }

    @Test
    void solveAndApply_batchesNotificationsAndClearsTheFlagOnceWhenTwoAssignmentsShareASession() {
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.RESCHEDULE);
        PrayerSession sharedSession = new PrayerSession();
        sharedSession.setId(1L);
        sharedSession.setDate(LocalDate.of(2026, 9, 6));
        sharedSession.setRequiresRescheduling(true);
        PrayerAssignment moderatorAssignment = new PrayerAssignment();
        moderatorAssignment.setId(1L);
        moderatorAssignment.setRole(PrayerAssignmentRole.MODERATOR);
        moderatorAssignment.setSession(sharedSession);
        PrayerAssignment preacherAssignment = new PrayerAssignment();
        preacherAssignment.setId(2L);
        preacherAssignment.setRole(PrayerAssignmentRole.PREACHER);
        preacherAssignment.setSession(sharedSession);
        User newUser = testUser("u1");
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(
            List.of(moderatorAssignment, preacherAssignment)
        );
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(newUser));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(moderatorAssignment, newUser), solvedClone(preacherAssignment, newUser))
            );
            solved.setScore(HardSoftScore.of(0, 0));
            return solved;
        });

        service.solveAndApply(9L);

        verify(notificationService).notifyAssignmentsPublished(
            eq(newUser),
            argThat(list -> list.size() == 2 && list.containsAll(List.of(moderatorAssignment, preacherAssignment)))
        );
        verify(notificationService, never()).notifyAssignmentsRemoved(any(), any());
        assertThat(sharedSession.isRequiresRescheduling()).isFalse();
    }

    @Test
    void solveAndApply_skipsSolvingWhenNoEligibleUserExists() {
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.MANUAL);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(
            List.of(assignment(1L, null), assignment(2L, null))
        );
        when(userRepository.findAllEligibleActive()).thenReturn(List.of());

        boolean feasible = service.solveAndApply(9L);

        assertThat(feasible).isFalse();
        assertThat(generation.getHardScore()).isEqualTo(-2);
        assertThat(generation.getSolverDurationMs()).isZero();
        assertThat(generation.getErrorMessage()).contains("No active user has any capability");
        verify(solverService, never()).solve(any(), any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void solveAndApply_throwsWhenGenerationIsMissing() {
        when(rosterGenerationRepository.findById(404L)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
            com.prayerroster.web.rest.errors.EntityNotFoundException.class,
            () -> service.solveAndApply(404L)
        );
    }

    @Test
    void markFailed_setsStatusAndErrorMessageAndSaves() {
        RosterGeneration generation = generation(roster(), RosterGenerationTrigger.MANUAL);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));

        service.markFailed(9L, "Timefold blew up");

        assertThat(generation.getStatus()).isEqualTo(RosterGenerationStatus.FAILED);
        assertThat(generation.getErrorMessage()).isEqualTo("Timefold blew up");
        verify(rosterGenerationRepository).save(generation);
    }

    @Test
    void markFailed_doesNothingWhenGenerationNoLongerExists() {
        when(rosterGenerationRepository.findById(404L)).thenReturn(Optional.empty());

        service.markFailed(404L, "Timefold blew up");

        verify(rosterGenerationRepository, never()).save(any());
    }
}
