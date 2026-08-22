package com.prayerroster.service;

import com.prayerroster.domain.*;
import com.prayerroster.repository.PrayerSessionRepository;
import com.prayerroster.repository.RosterGenerationRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.repository.WeeklyPrayerConfigurationRepository;
import com.prayerroster.service.dto.GenerateRosterRequest;
import com.prayerroster.service.dto.RosterDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the recurring weekly template into actual dated {@link PrayerSession}/{@link
 * PrayerAssignment} rows for a period (see docs/phase1-architecture.md sections 4-5, 33). Every
 * date-to-config lookup happens up front, in memory, against the whole (small) version history
 * loaded in one query - never one query per date - so this stays O(1) queries regardless of period
 * length. Validated entirely before anything is persisted: either the whole period generates
 * cleanly in one transaction, or nothing is written at all - no partial/orphaned rows to clean up.
 * <p>
 * Every {@link PrayerAssignment} row is created with a {@code null} user - Sprint 5's Timefold
 * solver fills that in. The {@link Roster} produced here is always {@link RosterStatus#DRAFT}: this
 * sprint has no solver to auto-publish on.
 */
@Service
@Transactional
public class RosterGenerationService {

    private static final String ENTITY_NAME = "roster";

    private final RosterRepository rosterRepository;
    private final RosterGenerationRepository rosterGenerationRepository;
    private final PrayerSessionRepository prayerSessionRepository;
    private final WeeklyPrayerConfigurationRepository weeklyPrayerConfigurationRepository;

    public RosterGenerationService(
        RosterRepository rosterRepository,
        RosterGenerationRepository rosterGenerationRepository,
        PrayerSessionRepository prayerSessionRepository,
        WeeklyPrayerConfigurationRepository weeklyPrayerConfigurationRepository
    ) {
        this.rosterRepository = rosterRepository;
        this.rosterGenerationRepository = rosterGenerationRepository;
        this.prayerSessionRepository = prayerSessionRepository;
        this.weeklyPrayerConfigurationRepository = weeklyPrayerConfigurationRepository;
    }

    public RosterDTO generate(GenerateRosterRequest request) {
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
        generation.setTrigger(RosterGenerationTrigger.MANUAL);
        generation.setPlanningFrom(from);
        generation.setPlanningTo(to);
        generation.setStatus(RosterGenerationStatus.COMPLETED);
        generation.setRegenerated(false);
        generation = rosterGenerationRepository.save(generation);

        for (RequiredSession required : requiredSessions) {
            createSession(roster, generation, required);
        }

        return RosterDTO.from(roster);
    }

    private void createSession(Roster roster, RosterGeneration generation, RequiredSession required) {
        PrayerSession session = new PrayerSession();
        session.setRoster(roster);
        session.setDate(required.date());
        session.setDayOfWeek(required.date().getDayOfWeek());
        session.setRequiresPreacher(required.requiresPreacher());

        PrayerAssignment moderatorAssignment = new PrayerAssignment();
        moderatorAssignment.setSession(session);
        moderatorAssignment.setRole(PrayerAssignmentRole.MODERATOR);
        moderatorAssignment.setGeneration(generation);
        session.getAssignments().add(moderatorAssignment);

        if (required.requiresPreacher()) {
            PrayerAssignment preacherAssignment = new PrayerAssignment();
            preacherAssignment.setSession(session);
            preacherAssignment.setRole(PrayerAssignmentRole.PREACHER);
            preacherAssignment.setGeneration(generation);
            session.getAssignments().add(preacherAssignment);
        }

        prayerSessionRepository.save(session);
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
