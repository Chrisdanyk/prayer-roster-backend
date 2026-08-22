package com.prayerroster.service;

import com.prayerroster.domain.RosterGenerationTrigger;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.service.dto.GenerateRosterRequest;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import java.time.LocalDate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps roster generation rolling ~2 months ahead of today, on top of admin-triggered generation
 * for any custom range (see docs/phase1-architecture.md's Horizon decision). ShedLock guards
 * against wasted duplicate work if this app ever runs more than one instance - correctness itself
 * comes from {@link com.prayerroster.repository.PrayerSessionRepository#existsByDateBetween}'s
 * overlap check inside {@link RosterGenerationService#generate}, the same pattern established for
 * the Sprint 7 reminder/email sweeps.
 */
@Service
public class RollingRosterGenerationService {

    private static final Logger LOG = LoggerFactory.getLogger(RollingRosterGenerationService.class);

    static final int HORIZON_MONTHS = 2;

    private final RosterRepository rosterRepository;
    private final RosterGenerationService rosterGenerationService;

    public RollingRosterGenerationService(RosterRepository rosterRepository, RosterGenerationService rosterGenerationService) {
        this.rosterRepository = rosterRepository;
        this.rosterGenerationService = rosterGenerationService;
    }

    @Scheduled(cron = "${application.roster-generation.cron:0 0 3 1 * *}")
    @SchedulerLock(name = "rollingRosterGeneration", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void generateRollingWindow() {
        LocalDate today = LocalDate.now();
        LocalDate from = rosterRepository.findTopByOrderByPeriodToDesc().map(roster -> roster.getPeriodTo().plusDays(1)).orElse(today);
        LocalDate to = today.plusMonths(HORIZON_MONTHS);

        if (from.isAfter(to)) {
            LOG.debug("Rolling roster generation: already covered through {}, nothing to do", from.minusDays(1));
            return;
        }

        try {
            rosterGenerationService.generate(new GenerateRosterRequest(from, to), RosterGenerationTrigger.SCHEDULED_CRON);
            LOG.info("Rolling roster generation covered {} to {}", from, to);
        } catch (BadRequestAlertException e) {
            // Most likely no weekly configuration covers part of the window yet - an admin needs to
            // act, not a reason to crash the scheduler. Next month's run will retry the same gap.
            LOG.warn("Rolling roster generation skipped for {} to {}: {}", from, to, e.getMessage());
        }
    }
}
