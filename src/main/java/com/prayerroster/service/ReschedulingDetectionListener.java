package com.prayerroster.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges availability/deactivation changes to rescheduling detection, deliberately waiting for the
 * triggering transaction to commit first ({@link TransactionPhase#AFTER_COMMIT}) - detection must
 * see the just-created availability row (or just-deactivated user) as durably committed data, not
 * as still-uncommitted state in a transaction it can't see into. See
 * docs/phase1-architecture.md section 12. {@link ReschedulingDetectionService} itself guarantees it
 * never throws, so there is nothing to catch here.
 */
@Component
public class ReschedulingDetectionListener {

    private final ReschedulingDetectionService reschedulingDetectionService;

    public ReschedulingDetectionListener(ReschedulingDetectionService reschedulingDetectionService) {
        this.reschedulingDetectionService = reschedulingDetectionService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAvailabilityChanged(UserAvailabilityChangedEvent event) {
        reschedulingDetectionService.onAvailabilityCreated(event.userId(), event.startDate(), event.endDate());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDeactivated(UserDeactivatedEvent event) {
        reschedulingDetectionService.onUserDeactivated(event.userId());
    }
}
