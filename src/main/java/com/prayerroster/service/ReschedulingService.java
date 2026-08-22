package com.prayerroster.service;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.domain.Roster;
import com.prayerroster.domain.RosterGeneration;
import com.prayerroster.domain.RosterGenerationStatus;
import com.prayerroster.domain.RosterGenerationTrigger;
import com.prayerroster.domain.RosterStatus;
import com.prayerroster.repository.PrayerSessionRepository;
import com.prayerroster.repository.RosterGenerationRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.service.dto.RosterDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-solves an already-published {@link Roster} that has one or more sessions flagged {@code
 * requiresRescheduling}, pinning every other assignment in place via Timefold's {@code @PlanningPin}
 * so the solve structurally cannot touch anything outside the affected sessions (see
 * docs/phase1-architecture.md section 12) - a hard guarantee, not a soft preference. Reachable both
 * automatically (see {@link ReschedulingDetectionService}) and via the manual "force it now" endpoint,
 * which reuses whatever sessions are currently flagged rather than taking its own scope.
 */
@Service
@Transactional
public class ReschedulingService {

    private static final String ENTITY_NAME = "roster";

    private final RosterRepository rosterRepository;
    private final RosterGenerationRepository rosterGenerationRepository;
    private final PrayerSessionRepository prayerSessionRepository;
    private final RosterSolvingService rosterSolvingService;

    public ReschedulingService(
        RosterRepository rosterRepository,
        RosterGenerationRepository rosterGenerationRepository,
        PrayerSessionRepository prayerSessionRepository,
        RosterSolvingService rosterSolvingService
    ) {
        this.rosterRepository = rosterRepository;
        this.rosterGenerationRepository = rosterGenerationRepository;
        this.prayerSessionRepository = prayerSessionRepository;
        this.rosterSolvingService = rosterSolvingService;
    }

    public RosterDTO reschedule(Long rosterId, String reason) {
        Roster roster = rosterRepository.findById(rosterId).orElseThrow(() -> new EntityNotFoundException("Roster not found: " + rosterId));
        if (roster.getStatus() != RosterStatus.REQUIRES_RESCHEDULING) {
            throw new BadRequestAlertException("Roster does not currently require rescheduling", ENTITY_NAME, "notRequiresRescheduling");
        }

        List<PrayerSession> sessions = prayerSessionRepository.findByRosterIdWithAssignments(rosterId);
        List<PrayerAssignment> assignments = sessions.stream().flatMap(session -> session.getAssignments().stream()).toList();
        if (assignments.stream().noneMatch(assignment -> assignment.getSession().isRequiresRescheduling())) {
            throw new BadRequestAlertException("No session on this roster is flagged for rescheduling", ENTITY_NAME, "nothingToReschedule");
        }

        // Pin every assignment except those on a flagged session - Timefold structurally cannot move
        // an unaffected, already-published assignment.
        for (PrayerAssignment assignment : assignments) {
            assignment.setLocked(!assignment.getSession().isRequiresRescheduling());
        }

        RosterGeneration generation = new RosterGeneration();
        generation.setRoster(roster);
        generation.setTrigger(RosterGenerationTrigger.RESCHEDULE);
        generation.setPlanningFrom(roster.getPeriodFrom());
        generation.setPlanningTo(roster.getPeriodTo());
        generation.setStatus(RosterGenerationStatus.RUNNING);
        generation.setRegenerated(true);
        generation.setRescheduleReason(reason);
        RosterGeneration savedGeneration = rosterGenerationRepository.save(generation);
        assignments.forEach(assignment -> assignment.setGeneration(savedGeneration));

        boolean feasible = rosterSolvingService.solveAndApply(
            roster,
            savedGeneration,
            assignments,
            roster.getPeriodFrom(),
            roster.getPeriodTo(),
            RosterStatus.REQUIRES_RESCHEDULING
        );

        if (feasible) {
            sessions.stream().filter(PrayerSession::isRequiresRescheduling).forEach(session -> session.setRequiresRescheduling(false));
        }

        return RosterDTO.from(roster);
    }
}
