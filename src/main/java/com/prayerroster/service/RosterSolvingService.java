package com.prayerroster.service;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.domain.Roster;
import com.prayerroster.domain.RosterGeneration;
import com.prayerroster.domain.RosterGenerationStatus;
import com.prayerroster.domain.RosterGenerationTrigger;
import com.prayerroster.domain.RosterStatus;
import com.prayerroster.domain.User;
import com.prayerroster.domain.UserAvailability;
import com.prayerroster.repository.PrayerAssignmentRepository;
import com.prayerroster.repository.RosterGenerationRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.repository.UserAvailabilityRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.scheduling.RosterSolution;
import com.prayerroster.scheduling.SolverService;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared "solve this planning problem and apply the outcome" orchestration, used for both initial
 * roster generation and rescheduling (see docs/phase1-architecture.md sections 6, 12, 33). Invoked
 * by {@link RosterSolvingListener} once it picks up a {@link RosterGenerationRequestedEvent},
 * {@link #solveAndApply(Long)} is fully self-contained: given just a {@link RosterGeneration} id, it
 * re-fetches the roster and its assignments itself, and derives which {@link RosterStatus} the
 * roster falls back to when the solve is infeasible from {@link RosterGeneration#getTrigger()} -
 * {@code DRAFT} for a brand-new roster that was never published, {@code REQUIRES_RESCHEDULING} for
 * one that was already live and still needs attention. For a {@link RosterGenerationTrigger#RESCHEDULE}
 * trigger specifically, a successful solve also clears {@code requiresRescheduling} on every affected
 * session - a responsibility that used to live in {@code ReschedulingService} before this method
 * became self-contained.
 * <p>
 * On a feasible solve, every assignment whose {@code user} actually changed (a fresh fill from
 * initial generation, or a reassignment from rescheduling) triggers a notification: the newly
 * assigned user is told, and - if a different user previously held that slot - so is the person
 * who lost it (see docs/phase1-architecture.md section 13). Unaffected, pinned assignments never
 * generate noise.
 */
@Service
@Transactional
public class RosterSolvingService {

    private final RosterRepository rosterRepository;
    private final RosterGenerationRepository rosterGenerationRepository;
    private final PrayerAssignmentRepository prayerAssignmentRepository;
    private final UserRepository userRepository;
    private final UserAvailabilityRepository userAvailabilityRepository;
    private final SolverService solverService;
    private final NotificationService notificationService;

    public RosterSolvingService(
        RosterRepository rosterRepository,
        RosterGenerationRepository rosterGenerationRepository,
        PrayerAssignmentRepository prayerAssignmentRepository,
        UserRepository userRepository,
        UserAvailabilityRepository userAvailabilityRepository,
        SolverService solverService,
        NotificationService notificationService
    ) {
        this.rosterRepository = rosterRepository;
        this.rosterGenerationRepository = rosterGenerationRepository;
        this.prayerAssignmentRepository = prayerAssignmentRepository;
        this.userRepository = userRepository;
        this.userAvailabilityRepository = userAvailabilityRepository;
        this.solverService = solverService;
        this.notificationService = notificationService;
    }

    public boolean solveAndApply(Long generationId) {
        RosterGeneration generation = rosterGenerationRepository
            .findById(generationId)
            .orElseThrow(() -> new EntityNotFoundException("Roster generation not found: " + generationId));
        Roster roster = generation.getRoster();
        List<PrayerAssignment> assignments = prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(generationId);
        LocalDate availabilityWindowFrom = generation.getPlanningFrom();
        LocalDate availabilityWindowTo = generation.getPlanningTo();
        RosterStatus statusOnInfeasible = generation.getTrigger() == RosterGenerationTrigger.RESCHEDULE
            ? RosterStatus.REQUIRES_RESCHEDULING
            : RosterStatus.DRAFT;

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
            // Timefold refuses to solve an empty value range outright, and there is nothing to gain
            // from trying anyway: with zero eligible users nothing can ever be filled, so
            // short-circuit straight to an infeasible outcome instead of invoking the solver.
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
            Map<User, List<PrayerAssignment>> newlyPublished = new LinkedHashMap<>();
            Map<User, List<PrayerAssignment>> newlyRemoved = new LinkedHashMap<>();
            for (PrayerAssignment managed : assignments) {
                User previousUser = managed.getUser();
                // A feasible solve (hardScore == 0) mathematically guarantees newUser is non-null
                // here: everyAssignmentMustBeFilled contributes a hard violation for any unfilled
                // assignment, so hardScore couldn't be zero if one existed. That matters because
                // newUser is used below as a key in newlyPublished (a LinkedHashMap) - a null key
                // there would silently pass a null recipient into a notification call.
                User newUser = solvedById.get(managed.getId()).getUser();
                if (!Objects.equals(previousUser, newUser)) {
                    managed.setUser(newUser);
                    if (previousUser != null) {
                        newlyRemoved.computeIfAbsent(previousUser, u -> new ArrayList<>()).add(managed);
                    }
                    newlyPublished.computeIfAbsent(newUser, u -> new ArrayList<>()).add(managed);
                }
            }
            newlyRemoved.forEach(notificationService::notifyAssignmentsRemoved);
            newlyPublished.forEach(notificationService::notifyAssignmentsPublished);

            generation.setStatus(RosterGenerationStatus.COMPLETED);
            roster.setStatus(RosterStatus.PUBLISHED);
            roster.setPublishedAt(Instant.now());
            if (generation.getTrigger() == RosterGenerationTrigger.RESCHEDULE) {
                assignments
                    .stream()
                    .map(PrayerAssignment::getSession)
                    .distinct()
                    .filter(PrayerSession::isRequiresRescheduling)
                    .forEach(session -> session.setRequiresRescheduling(false));
            }
        } else {
            generation.setStatus(RosterGenerationStatus.INFEASIBLE);
            generation.setErrorMessage(
                eligibleUsers.isEmpty()
                    ? "No active user has any capability (moderator or preacher)"
                    : solverService.explainHardConstraintViolations(solved)
            );
            roster.setStatus(statusOnInfeasible);
        }

        rosterRepository.save(roster);
        rosterGenerationRepository.save(generation);
        return feasible;
    }

    /**
     * Records a solve failure that {@link #solveAndApply(Long)} never got the chance to record
     * itself - e.g. a Timefold internal error, an OOM, or a DB error thrown mid-solve, all of which
     * roll back {@code solveAndApply}'s own transaction and take its writes with them. Runs in a
     * brand-new, independent transaction ({@code REQUIRES_NEW}) precisely so this write survives
     * that rollback. Never throws itself: if the generation has somehow already vanished by the
     * time this runs, that is logged-and-ignored territory, not a reason to mask the original
     * failure with a new one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long generationId, String errorMessage) {
        rosterGenerationRepository
            .findById(generationId)
            .ifPresent(generation -> {
                generation.setStatus(RosterGenerationStatus.FAILED);
                generation.setErrorMessage(errorMessage);
                rosterGenerationRepository.save(generation);
            });
    }
}
