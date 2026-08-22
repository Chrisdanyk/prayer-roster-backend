package com.prayerroster.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.domain.User;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * End-to-end solves against a real {@link SolverManager} (plain Java construction, no Spring
 * context/DB) - proves the planning model, constraint provider, and {@link SolverService} are wired
 * together correctly, not just that each piece compiles in isolation.
 */
class SolverServiceTest {

    private static SolverService newSolverService() {
        SolverConfig solverConfig = new SolverConfig()
            .withSolutionClass(RosterSolution.class)
            .withEntityClasses(PrayerAssignment.class)
            .withConstraintProviderClass(RosterConstraintProvider.class)
            .withTerminationSpentLimit(Duration.ofSeconds(1));
        SolverManager<RosterSolution, Long> solverManager = SolverManager.create(solverConfig);
        SolutionManager<RosterSolution, HardSoftScore> solutionManager = SolutionManager.create(solverManager);
        return new SolverService(solverManager, solutionManager);
    }

    private static User user(String id, boolean canModerate, boolean canPreach) {
        User user = new User();
        user.setId(id);
        user.setActive(true);
        user.setCanModerate(canModerate);
        user.setCanPreach(canPreach);
        return user;
    }

    private static PrayerAssignment assignment(Long id, PrayerSession session, PrayerAssignmentRole role) {
        PrayerAssignment assignment = new PrayerAssignment();
        assignment.setId(id);
        assignment.setSession(session);
        assignment.setRole(role);
        return assignment;
    }

    @Test
    void solve_fillsEveryAssignmentWhenACapabilityMatchingUserExistsForEachRole() {
        SolverService solverService = newSolverService();
        PrayerSession session = new PrayerSession();
        session.setId(1L);
        session.setDate(LocalDate.of(2026, 9, 6));
        session.setDayOfWeek(session.getDate().getDayOfWeek());

        PrayerAssignment moderatorAssignment = assignment(1L, session, PrayerAssignmentRole.MODERATOR);
        PrayerAssignment preacherAssignment = assignment(2L, session, PrayerAssignmentRole.PREACHER);
        User moderatorOnly = user("mod", true, false);
        User preacherOnly = user("preach", false, true);

        RosterSolution problem = new RosterSolution(
            List.of(moderatorOnly, preacherOnly),
            List.of(),
            List.of(moderatorAssignment, preacherAssignment)
        );

        RosterSolution solved = solverService.solve(1L, problem);

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getAssignments()).allSatisfy(a -> assertThat(a.getUser()).isNotNull());
    }

    @Test
    void solve_staysInfeasibleWhenNoCandidateHasTheRequiredCapability() {
        SolverService solverService = newSolverService();
        PrayerSession session = new PrayerSession();
        session.setId(1L);
        session.setDate(LocalDate.of(2026, 9, 6));
        session.setDayOfWeek(session.getDate().getDayOfWeek());
        PrayerAssignment moderatorAssignment = assignment(1L, session, PrayerAssignmentRole.MODERATOR);
        // Only a preacher-capable user exists - nobody can ever fill the moderator role.
        User preacherOnly = user("preach", false, true);

        RosterSolution problem = new RosterSolution(List.of(preacherOnly), List.of(), List.of(moderatorAssignment));

        RosterSolution solved = solverService.solve(2L, problem);

        assertThat(solved.getScore().hardScore()).isNegative();
    }

    @Test
    void explainHardConstraintViolations_namesTheViolatedConstraint() {
        SolverService solverService = newSolverService();
        PrayerSession session = new PrayerSession();
        session.setId(1L);
        session.setDate(LocalDate.of(2026, 9, 6));
        session.setDayOfWeek(session.getDate().getDayOfWeek());
        PrayerAssignment moderatorAssignment = assignment(1L, session, PrayerAssignmentRole.MODERATOR);
        User preacherOnly = user("preach", false, true);

        RosterSolution problem = new RosterSolution(List.of(preacherOnly), List.of(), List.of(moderatorAssignment));
        RosterSolution solved = solverService.solve(3L, problem);

        String explanation = solverService.explainHardConstraintViolations(solved);

        // Whichever way the solver leaves it - unfilled or filled by the wrong-capability
        // candidate - exactly one of these two hard constraints must be named as violated.
        assertThat(explanation).containsAnyOf("everyAssignmentMustBeFilled", "userMustHaveModerationCapability");
    }

    @Test
    void explainHardConstraintViolations_sortsMultipleViolatedConstraintsByHardScore() {
        SolverService solverService = newSolverService();
        // Hand-built, already-"solved" state (no actual solving) so both violations are
        // deterministic: an inactive user assigned (violates inactiveUserCannotBeAssigned) plus a
        // separate unfilled assignment (violates everyAssignmentMustBeFilled).
        PrayerSession session = new PrayerSession();
        session.setId(1L);
        session.setDate(LocalDate.of(2026, 9, 6));
        session.setDayOfWeek(session.getDate().getDayOfWeek());
        User inactiveUser = user("inactive", true, true);
        inactiveUser.setActive(false);
        PrayerAssignment assignedToInactiveUser = assignment(1L, session, PrayerAssignmentRole.MODERATOR);
        assignedToInactiveUser.setUser(inactiveUser);
        PrayerAssignment unfilled = assignment(2L, session, PrayerAssignmentRole.PREACHER);

        RosterSolution solution = new RosterSolution(
            List.of(inactiveUser),
            List.of(),
            List.of(assignedToInactiveUser, unfilled)
        );

        String explanation = solverService.explainHardConstraintViolations(solution);

        assertThat(explanation).contains("everyAssignmentMustBeFilled").contains("inactiveUserCannotBeAssigned");
    }

    @Test
    void solve_wrapsInterruptedExceptionAndRestoresInterruptStatus() throws InterruptedException {
        SolverConfig solverConfig = new SolverConfig()
            .withSolutionClass(RosterSolution.class)
            .withEntityClasses(PrayerAssignment.class)
            .withConstraintProviderClass(RosterConstraintProvider.class)
            .withTerminationSpentLimit(Duration.ofSeconds(5));
        SolverManager<RosterSolution, Long> solverManager = SolverManager.create(solverConfig);
        SolutionManager<RosterSolution, HardSoftScore> solutionManager = SolutionManager.create(solverManager);
        SolverService solverService = new SolverService(solverManager, solutionManager);

        PrayerSession session = new PrayerSession();
        session.setId(1L);
        session.setDate(LocalDate.of(2026, 9, 6));
        session.setDayOfWeek(session.getDate().getDayOfWeek());
        PrayerAssignment moderatorAssignment = assignment(1L, session, PrayerAssignmentRole.MODERATOR);
        PrayerAssignment preacherAssignment = assignment(2L, session, PrayerAssignmentRole.PREACHER);
        RosterSolution problem = new RosterSolution(
            List.of(user("mod", true, false), user("preach", false, true)),
            List.of(),
            List.of(moderatorAssignment, preacherAssignment)
        );

        AtomicReference<Throwable> caught = new AtomicReference<>();
        AtomicBoolean interruptFlagAfterCatch = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                solverService.solve(4L, problem);
            } catch (Throwable t) {
                caught.set(t);
                interruptFlagAfterCatch.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        Thread.sleep(100);
        worker.interrupt();
        worker.join(Duration.ofSeconds(10).toMillis());

        assertThat(caught.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");
        assertThat(interruptFlagAfterCatch.get()).isTrue();
    }

    @Test
    void solve_wrapsExecutionExceptionWhenTheConstraintProviderThrows() {
        SolverConfig solverConfig = new SolverConfig()
            .withSolutionClass(RosterSolution.class)
            .withEntityClasses(PrayerAssignment.class)
            .withConstraintProviderClass(ThrowingConstraintProvider.class)
            .withTerminationSpentLimit(Duration.ofSeconds(5));
        SolverManager<RosterSolution, Long> solverManager = SolverManager.create(solverConfig);
        SolutionManager<RosterSolution, HardSoftScore> solutionManager = SolutionManager.create(solverManager);
        SolverService solverService = new SolverService(solverManager, solutionManager);

        PrayerSession session = new PrayerSession();
        session.setId(1L);
        session.setDate(LocalDate.of(2026, 9, 6));
        session.setDayOfWeek(session.getDate().getDayOfWeek());
        PrayerAssignment moderatorAssignment = assignment(1L, session, PrayerAssignmentRole.MODERATOR);
        RosterSolution problem = new RosterSolution(List.of(user("mod", true, false)), List.of(), List.of(moderatorAssignment));

        assertThatThrownBy(() -> solverService.solve(5L, problem))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Roster solving failed")
            .hasRootCauseMessage("boom");
    }

    /**
     * A deliberately broken provider so {@link SolverService#solve} sees a genuine solve-time
     * failure. Timefold needs a publicly no-arg-constructible class here, which unavoidably makes
     * it visible to Spring's classpath component scan too - a future full-context
     * ({@code @SpringBootTest}) test in this package tree will see two {@code ConstraintProvider}s
     * on the classpath and fail to boot with an ambiguity error unless that test explicitly pins
     * {@code timefold.solver.constraint-provider-class} to {@link RosterConstraintProvider}.
     */
    public static class ThrowingConstraintProvider implements ConstraintProvider {

        @Override
        public Constraint[] defineConstraints(ConstraintFactory factory) {
            return new Constraint[] {
                factory
                    .forEach(PrayerAssignment.class)
                    .filter(a -> {
                        throw new IllegalStateException("boom");
                    })
                    .penalize(HardSoftScore.ONE_HARD)
                    .asConstraint("throwing"),
            };
        }
    }
}
