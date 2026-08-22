package com.prayerroster.scheduling;

import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverJob;
import ai.timefold.solver.core.api.solver.SolverManager;
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around Timefold's {@link SolverManager} (see docs/phase1-architecture.md section 6).
 * {@link #solve} blocks the caller until the solve finishes - Timefold runs it on its own internal
 * thread pool, not the caller's thread, so this is safe to call from within a transactional service
 * method without holding a DB connection open across the whole solve.
 */
@Service
public class SolverService {

    private final SolverManager<RosterSolution, Long> solverManager;
    private final SolutionManager<RosterSolution, HardSoftScore> solutionManager;

    public SolverService(
        SolverManager<RosterSolution, Long> solverManager,
        SolutionManager<RosterSolution, HardSoftScore> solutionManager
    ) {
        this.solverManager = solverManager;
        this.solutionManager = solutionManager;
    }

    public RosterSolution solve(Long problemId, RosterSolution problem) {
        SolverJob<RosterSolution, Long> job = solverManager.solve(problemId, problem);
        try {
            return job.getFinalBestSolution();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Roster solving was interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Roster solving failed", e.getCause());
        }
    }

    /**
     * A human-readable breakdown of every violated hard constraint, for the {@code errorMessage}
     * stored on an {@code INFEASIBLE} {@code RosterGeneration} (see docs/phase1-architecture.md
     * section 14 - infeasible solves must return a diagnostic, not just a bare failure).
     */
    public String explainHardConstraintViolations(RosterSolution solution) {
        return solutionManager
            .analyze(solution)
            .constraintMap()
            .values()
            .stream()
            .filter(constraint -> constraint.score().hardScore() < 0)
            .sorted(Comparator.comparingInt((ConstraintAnalysis<HardSoftScore> c) -> c.score().hardScore()))
            .map(constraint -> constraint.constraintRef().constraintName() + ": " + (-constraint.score().hardScore()) + " violation(s)")
            .collect(Collectors.joining("; "));
    }
}
