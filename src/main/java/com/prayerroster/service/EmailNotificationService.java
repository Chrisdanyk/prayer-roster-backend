package com.prayerroster.service;

import com.prayerroster.domain.EmailStatus;
import com.prayerroster.domain.Notification;
import com.prayerroster.repository.NotificationPreferenceRepository;
import com.prayerroster.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends the email side effect for a {@link Notification} and records the outcome on the same row
 * (see docs/phase1-architecture.md section 13). A send failure is caught and recorded, never
 * propagated - the notification itself was already created successfully regardless of email
 * deliverability. A periodic sweep retries {@code FAILED} rows up to {@link #MAX_RETRIES}.
 */
@Service
@Transactional
public class EmailNotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailNotificationService.class);
    static final int MAX_RETRIES = 5;

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final NotificationTextResolver textResolver;
    private final MailService mailService;

    public EmailNotificationService(
        NotificationRepository notificationRepository,
        NotificationPreferenceRepository notificationPreferenceRepository,
        NotificationTextResolver textResolver,
        MailService mailService
    ) {
        this.notificationRepository = notificationRepository;
        this.notificationPreferenceRepository = notificationPreferenceRepository;
        this.textResolver = textResolver;
        this.mailService = mailService;
    }

    public void sendForNotification(Long notificationId) {
        notificationRepository.findByIdWithRecipient(notificationId).filter(n -> n.getEmailStatus() == EmailStatus.PENDING).ifPresent(
            this::sendOrSkip
        );
    }

    @Scheduled(fixedDelayString = "${application.notifications.retry-sweep-interval-ms:900000}")
    @SchedulerLock(name = "notificationEmailRetrySweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void retryFailedEmails() {
        List<Notification> retryable = notificationRepository.findRetryableByEmailStatus(EmailStatus.FAILED, MAX_RETRIES);
        retryable.forEach(this::attemptSend);
    }

    private void sendOrSkip(Notification notification) {
        boolean emailEnabled = notificationPreferenceRepository
            .findByUserId(notification.getRecipient().getId())
            .map(pref -> pref.isEmailEnabled())
            .orElse(true);
        if (!emailEnabled) {
            notification.setEmailStatus(EmailStatus.SKIPPED);
            return;
        }
        attemptSend(notification);
    }

    private void attemptSend(Notification notification) {
        try {
            Locale locale = Locale.forLanguageTag(notification.getRecipient().getLangKey());
            String subject = textResolver.resolveSubject(notification, locale);
            String body = textResolver.resolveBody(notification, locale);
            mailService.sendEmail(notification.getRecipient().getEmail(), subject, body);
            notification.setEmailStatus(EmailStatus.SENT);
            notification.setEmailSentAt(Instant.now());
        } catch (RuntimeException e) {
            notification.setRetryCount(notification.getRetryCount() + 1);
            notification.setEmailStatus(EmailStatus.FAILED);
            LOG.warn("Failed to send notification email {} (attempt {})", notification.getId(), notification.getRetryCount(), e);
        }
    }
}
