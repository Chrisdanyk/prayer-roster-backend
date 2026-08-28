package com.prayerroster.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs the actual Timefold solve off the request thread, after the generation row that requested it
 * is durably committed - same shape as {@link EmailNotificationListener} (see
 * docs/superpowers/specs/2026-08-28-async-roster-operations-design.md). {@link
 * RosterSolvingService#solveAndApply} is itself {@code @Transactional}, so this runs in a genuinely
 * fresh transaction on this new thread.
 */
@Component
public class RosterSolvingListener {

    private final RosterSolvingService rosterSolvingService;

    public RosterSolvingListener(RosterSolvingService rosterSolvingService) {
        this.rosterSolvingService = rosterSolvingService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRosterGenerationRequested(RosterGenerationRequestedEvent event) {
        rosterSolvingService.solveAndApply(event.generationId());
    }
}
