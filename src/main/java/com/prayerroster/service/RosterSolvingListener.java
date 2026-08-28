package com.prayerroster.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>
 * A solve failure (a Timefold internal error, an OOM, a DB error, or the app restarting mid-solve)
 * rolls back {@code solveAndApply}'s own transaction and is otherwise lost to the {@code @Async}
 * executor's uncaught-exception handler - nothing would ever mark the generation {@code FAILED},
 * leaving it {@code RUNNING} forever. The catch below is deliberately broad ({@code Exception}, not
 * a narrower solver-specific type) because a solve failure's shape is not something this listener
 * can predict, and {@link RosterSolvingService#markFailed} records the failure in its own,
 * already-committed transaction so the record survives the rollback.
 */
@Component
public class RosterSolvingListener {

    private static final Logger LOG = LoggerFactory.getLogger(RosterSolvingListener.class);

    private final RosterSolvingService rosterSolvingService;

    public RosterSolvingListener(RosterSolvingService rosterSolvingService) {
        this.rosterSolvingService = rosterSolvingService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRosterGenerationRequested(RosterGenerationRequestedEvent event) {
        try {
            rosterSolvingService.solveAndApply(event.generationId());
        } catch (Exception e) {
            LOG.error("Roster solve failed for generation {}", event.generationId(), e);
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            rosterSolvingService.markFailed(event.generationId(), message);
        }
    }
}
