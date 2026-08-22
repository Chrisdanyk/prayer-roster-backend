package com.prayerroster.service;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.Roster;
import com.prayerroster.domain.RosterGeneration;
import com.prayerroster.domain.RosterGenerationStatus;
import com.prayerroster.domain.RosterStatus;
import com.prayerroster.domain.User;
import com.prayerroster.domain.UserAvailability;
import com.prayerroster.repository.RosterGenerationRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.repository.UserAvailabilityRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.scheduling.RosterSolution;
import com.prayerroster.scheduling.SolverService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Shared "solve this planning problem and apply the outcome" orchestration, used by both initial
 * roster generation and rescheduling (see docs/phase1-architecture.md sections 6, 12, 33) - the two
 * differ only in how they arrive at their {@code assignments}/{@code generation} row and in which
 * {@link RosterStatus} the roster falls back to when the solve is infeasible: {@code DRAFT} for a
 * brand-new roster that was never published, {@code REQUIRES_RESCHEDULING} for one that was already
 * live and still needs attention.
 * <p>
 * On a feasible solve, every assignment whose {@code user} actually changed (a fresh fill from
 * initial generation, or a reassignment from rescheduling) triggers a notification: the newly
 * assigned user is told, and - if a different user previously held that slot - so is the person
 * who lost it (see docs/phase1-architecture.md section 13). Unaffected, pinned assignments never
 * generate noise.
 */
@Service
public class RosterSolvingService {

    private final RosterRepository rosterRepository;
    private final RosterGenerationRepository rosterGenerationRepository;
    private final UserRepository userRepository;
    private final UserAvailabilityRepository userAvailabilityRepository;
    private final SolverService solverService;
    private final NotificationService notificationService;

    public RosterSolvingService(
        RosterRepository rosterRepository,
        RosterGenerationRepository rosterGenerationRepository,
        UserRepository userRepository,
        UserAvailabilityRepository userAvailabilityRepository,
        SolverService solverService,
        NotificationService notificationService
    ) {
        this.rosterRepository = rosterRepository;
        this.rosterGenerationRepository = rosterGenerationRepository;
        this.userRepository = userRepository;
        this.userAvailabilityRepository = userAvailabilityRepository;
        this.solverService = solverService;
        this.notificationService = notificationService;
    }

    /**
     * Solves {@code assignments} against every currently-eligible user and active unavailability
     * overlapping [{@code availabilityWindowFrom}, {@code availabilityWindowTo}], then applies the
     * outcome: on a feasible solve, fills in the assignments and publishes {@code roster}; otherwise
     * records a diagnostic on {@code generation} and falls {@code roster} back to
     * {@code statusOnInfeasible}. Returns whether the solve was feasible.
     */
    public boolean solveAndApply(
        Roster roster,
        RosterGeneration generation,
        List<PrayerAssignment> assignments,
        LocalDate availabilityWindowFrom,
        LocalDate availabilityWindowTo,
        RosterStatus statusOnInfeasible
    ) {
        List<User> eligibleUsers = userRepository.findAllEligibleActive();
        List<UserAvailability> unavailabilities = userAvailabilityRepository.findActiveOverlapping(
            availabilityWindowFrom,
            availabilityWindowTo
        );

        int hardScore;
        int softScore;
        long solverDurationMs;
        RosterSolution solved = null;
        if (eligibleUsers.isEmpty()) {
            // Timefold refuses to solve an empty value range outright, and structurally there is
            // nothing to gain from trying anyway - zero eligible users can never fill anything.
            hardScore = -assignments.size();
            softScore = 0;
            solverDurationMs = 0;
        } else {
            RosterSolution problem = new RosterSolution(eligibleUsers, unavailabilities, assignments);
            Instant solveStart = Instant.now();
            solved = solverService.solve(generation.getId(), problem);
            solverDurationMs = Duration.between(solveStart, Instant.now()).toMillis();
            hardScore = solved.getScore().hardScore();
            softScore = solved.getScore().softScore();
        }
        generation.setSolverDurationMs(solverDurationMs);
        generation.setHardScore(hardScore);
        generation.setSoftScore(softScore);
        generation.setFeasible(hardScore == 0);

        boolean feasible = hardScore == 0;
        if (feasible) {
            Map<Long, PrayerAssignment> solvedById = solved
                .getAssignments()
                .stream()
                .collect(Collectors.toMap(PrayerAssignment::getId, Function.identity()));
            for (PrayerAssignment managed : assignments) {
                User previousUser = managed.getUser();
                // A feasible solve (hardScore == 0) mathematically guarantees newUser is never null
                // here: everyAssignmentMustBeFilled contributes a hard violation for every unfilled
                // assignment, so hardScore couldn't be zero if one existed.
                User newUser = solvedById.get(managed.getId()).getUser();
                if (!Objects.equals(previousUser, newUser)) {
                    managed.setUser(newUser);
                    if (previousUser != null) {
                        notificationService.notifyAssignmentRemoved(previousUser, managed);
                    }
                    notificationService.notifyAssignmentPublished(managed);
                }
            }
            generation.setStatus(RosterGenerationStatus.COMPLETED);
            roster.setStatus(RosterStatus.PUBLISHED);
            roster.setPublishedAt(Instant.now());
        } else {
            generation.setStatus(RosterGenerationStatus.INFEASIBLE);
            generation.setErrorMessage(
                solved != null
                    ? solverService.explainHardConstraintViolations(solved)
                    : "No active user has any capability (canModerate/canPreach) - configure at least one eligible user first"
            );
            roster.setStatus(statusOnInfeasible);
        }
        rosterRepository.save(roster);
        rosterGenerationRepository.save(generation);
        return feasible;
    }
}
