package com.prayerroster.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges notification creation to the email side effect. {@code @Async} dispatches this to its own
 * thread with no ambient transaction, so - unlike the pitfall found and fixed in
 * {@code ReschedulingDetectionService} (Sprint 6), where an {@code @Transactional} call made
 * synchronously from an {@code AFTER_COMMIT} callback silently lost its writes - there is no
 * same-thread transaction-resolution ambiguity here: {@link EmailNotificationService}'s own
 * {@code @Transactional} method starts a genuinely fresh transaction on this new thread.
 * {@code AFTER_COMMIT} still matters on its own: it guarantees the notification row this reads is
 * already durably committed.
 */
@Component
public class EmailNotificationListener {

    private final EmailNotificationService emailNotificationService;

    public EmailNotificationListener(EmailNotificationService emailNotificationService) {
        this.emailNotificationService = emailNotificationService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        emailNotificationService.sendForNotification(event.notificationId());
    }
}
