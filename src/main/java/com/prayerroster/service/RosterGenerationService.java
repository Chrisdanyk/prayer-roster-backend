package com.prayerroster.service;

import com.prayerroster.domain.*;
import com.prayerroster.repository.PrayerAssignmentRepository;
import com.prayerroster.repository.PrayerSessionRepository;
import com.prayerroster.repository.RosterGenerationRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.repository.WeeklyPrayerConfigurationRepository;
import com.prayerroster.service.dto.GenerateRosterRequest;
import com.prayerroster.service.dto.RosterDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the recurring weekly template into actual dated {@link PrayerSession}/{@link
 * PrayerAssignment} rows for a period, then hands the resulting planning problem to Timefold via
 * {@link RosterSolvingService} (see docs/phase1-architecture.md sections 4-6, 11, 33). Every
 * date-to-config lookup happens up front, in memory, against the whole (small) version history
 * loaded in one query - never one query per date - so this stays O(1) queries regardless of period
 * length. The whole period is validated before anything is persisted: either it generates and
 * solves cleanly in one transaction, or nothing is written at all.
 * <p>
 * On a feasible solve, assignments are filled in and the {@link Roster} auto-publishes (per the
 * roster lifecycle decision - no manual review step for the happy path). On an infeasible solve,
 * the {@link RosterGeneration} records why (a per-constraint violation breakdown) and the
 * {@link Roster} stays {@link RosterStatus#DRAFT} for an admin to investigate.
 */
@Service
@Transactional
public class RosterGenerationService {

    private static final String ENTITY_NAME = "roster";

    private final RosterRepository rosterRepository;
    private final RosterGenerationRepository rosterGenerationRepository;
    private final PrayerSessionRepository prayerSessionRepository;
    private final PrayerAssignmentRepository prayerAssignmentRepository;
    private final WeeklyPrayerConfigurationRepository weeklyPrayerConfigurationRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RosterGenerationService(
        RosterRepository rosterRepository,
        RosterGenerationRepository rosterGenerationRepository,
        PrayerSessionRepository prayerSessionRepository,
        PrayerAssignmentRepository prayerAssignmentRepository,
        WeeklyPrayerConfigurationRepository weeklyPrayerConfigurationRepository,
        ApplicationEventPublisher eventPublisher
    ) {
        this.rosterRepository = rosterRepository;
        this.rosterGenerationRepository = rosterGenerationRepository;
        this.prayerSessionRepository = prayerSessionRepository;
        this.prayerAssignmentRepository = prayerAssignmentRepository;
        this.weeklyPrayerConfigurationRepository = weeklyPrayerConfigurationRepository;
        this.eventPublisher = eventPublisher;
    }

    public RosterDTO generate(GenerateRosterRequest request) {
        return generate(request, RosterGenerationTrigger.MANUAL);
    }

    public RosterDTO generate(GenerateRosterRequest request, RosterGenerationTrigger trigger) {
        LocalDate from = request.from();
        LocalDate to = request.to();
        if (from.isAfter(to)) {
            throw new BadRequestAlertException("from must not be after to", ENTITY_NAME, "invalidPeriod");
        }
        if (prayerSessionRepository.existsByDateBetween(from, to)) {
            throw new BadRequestAlertException(
                "A prayer session already exists somewhere in this period - periods must not overlap",
                ENTITY_NAME,
                "periodOverlap"
            );
        }

        List<WeeklyPrayerConfiguration> configVersions = weeklyPrayerConfigurationRepository.findAllWithDaysOrderByEffectiveFromDesc();
        List<LocalDate> dates = from.datesUntil(to.plusDays(1)).toList();
        List<RequiredSession> requiredSessions = dates.stream().map(date -> resolveRequiredSession(date, configVersions)).toList();

        Roster roster = new Roster();
        roster.setPeriodFrom(from);
        roster.setPeriodTo(to);
        roster.setStatus(RosterStatus.DRAFT);
        roster = rosterRepository.save(roster);

        RosterGeneration generation = new RosterGeneration();
        generation.setRoster(roster);
        generation.setTrigger(trigger);
        generation.setPlanningFrom(from);
        generation.setPlanningTo(to);
        generation.setStatus(RosterGenerationStatus.RUNNING);
        generation.setRegenerated(false);
        generation = rosterGenerationRepository.save(generation);

        List<PrayerAssignment> assignments = new ArrayList<>();
        for (RequiredSession required : requiredSessions) {
            assignments.addAll(createSession(roster, generation, required));
        }

        eventPublisher.publishEvent(new RosterGenerationRequestedEvent(generation.getId()));

        return RosterDTO.from(roster);
    }

    private List<PrayerAssignment> createSession(Roster roster, RosterGeneration generation, RequiredSession required) {
        PrayerSession session = new PrayerSession();
        session.setRoster(roster);
        session.setDate(required.date());
        session.setDayOfWeek(required.date().getDayOfWeek());
        session.setRequiresPreacher(required.requiresPreacher());
        session = prayerSessionRepository.save(session);

        List<PrayerAssignment> created = new ArrayList<>();

        PrayerAssignment moderatorAssignment = new PrayerAssignment();
        moderatorAssignment.setSession(session);
        moderatorAssignment.setRole(PrayerAssignmentRole.MODERATOR);
        moderatorAssignment.setGeneration(generation);
        moderatorAssignment = prayerAssignmentRepository.save(moderatorAssignment);
        session.getAssignments().add(moderatorAssignment);
        created.add(moderatorAssignment);

        if (required.requiresPreacher()) {
            PrayerAssignment preacherAssignment = new PrayerAssignment();
            preacherAssignment.setSession(session);
            preacherAssignment.setRole(PrayerAssignmentRole.PREACHER);
            preacherAssignment.setGeneration(generation);
            preacherAssignment = prayerAssignmentRepository.save(preacherAssignment);
            session.getAssignments().add(preacherAssignment);
            created.add(preacherAssignment);
        }

        return created;
    }

    private RequiredSession resolveRequiredSession(LocalDate date, List<WeeklyPrayerConfiguration> configVersions) {
        WeeklyPrayerConfiguration applicable = configVersions
            .stream()
            .filter(version -> !version.getEffectiveFrom().isAfter(date) && (version.getEffectiveTo() == null || !version.getEffectiveTo().isBefore(date)))
            .findFirst()
            .orElseThrow(() -> new BadRequestAlertException(
                "No weekly prayer configuration covers " + date + " - configure the weekly pattern first",
                ENTITY_NAME,
                "noConfigurationForDate"
            ));

        boolean requiresPreacher = applicable
            .getDays()
            .stream()
            .filter(day -> day.getDayOfWeek() == date.getDayOfWeek())
            .findFirst()
            .map(WeeklyPrayerConfigurationDay::isRequiresPreacher)
            .orElseThrow(() -> new BadRequestAlertException(
                "Weekly prayer configuration is missing a day setting for " + date.getDayOfWeek(),
                ENTITY_NAME,
                "incompleteConfiguration"
            ));

        return new RequiredSession(date, requiresPreacher);
    }

    private record RequiredSession(LocalDate date, boolean requiresPreacher) {}
}
