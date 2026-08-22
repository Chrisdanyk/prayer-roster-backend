package com.prayerroster.service;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.ReminderConfiguration;
import com.prayerroster.domain.ReminderSent;
import com.prayerroster.repository.PrayerAssignmentRepository;
import com.prayerroster.repository.ReminderConfigurationRepository;
import com.prayerroster.repository.ReminderSentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily reminder sweep (see docs/phase1-architecture.md section 13). For each active {@link
 * ReminderConfiguration} offset, finds every filled assignment on the matching future date and
 * sends a reminder - "sends" here means: create the {@link ReminderSent} ledger row first, in its
 * own transaction, and only create the actual notification if that insert wins. The {@code
 * unique(assignment_id, reminder_configuration_id)} constraint on {@code reminder_sent} is what
 * makes this genuinely idempotent even if the sweep somehow runs twice for the same day - not just
 * the {@code @SchedulerLock}, which only prevents *concurrent* double-firing across instances, not a
 * second run after the first already completed.
 */
@Service
public class ReminderService {

    private static final Logger LOG = LoggerFactory.getLogger(ReminderService.class);

    private final ReminderConfigurationRepository reminderConfigurationRepository;
    private final PrayerAssignmentRepository prayerAssignmentRepository;
    private final ReminderSentRepository reminderSentRepository;
    private final NotificationService notificationService;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ReminderService(
        ReminderConfigurationRepository reminderConfigurationRepository,
        PrayerAssignmentRepository prayerAssignmentRepository,
        ReminderSentRepository reminderSentRepository,
        NotificationService notificationService,
        PlatformTransactionManager transactionManager
    ) {
        this.reminderConfigurationRepository = reminderConfigurationRepository;
        this.prayerAssignmentRepository = prayerAssignmentRepository;
        this.reminderSentRepository = reminderSentRepository;
        this.notificationService = notificationService;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(cron = "${application.reminders.cron:0 0 8 * * *}")
    @SchedulerLock(name = "reminderSweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void sendDueReminders() {
        LocalDate today = LocalDate.now();
        for (ReminderConfiguration configuration : reminderConfigurationRepository.findByActiveTrue()) {
            LocalDate targetDate = today.plusDays(configuration.getDaysBefore());
            List<PrayerAssignment> assignments = prayerAssignmentRepository.findPublishedAssignmentsForDate(targetDate);
            for (PrayerAssignment assignment : assignments) {
                sendReminderSafely(assignment, configuration);
            }
        }
    }

    private void sendReminderSafely(PrayerAssignment assignment, ReminderConfiguration configuration) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> recordAndNotify(assignment, configuration));
        } catch (DataIntegrityViolationException alreadySent) {
            LOG.debug(
                "Reminder already sent for assignment {} / offset {} day(s) - skipping",
                assignment.getId(),
                configuration.getDaysBefore()
            );
        }
    }

    private void recordAndNotify(PrayerAssignment assignment, ReminderConfiguration configuration) {
        ReminderSent sent = new ReminderSent();
        sent.setAssignment(assignment);
        sent.setReminderConfiguration(configuration);
        sent.setSentAt(Instant.now());
        // Flush immediately so a unique-constraint race surfaces here, before any notification is
        // created - never leave a duplicate notification behind for a reminder that "lost" the race.
        reminderSentRepository.saveAndFlush(sent);
        notificationService.notifyAssignmentReminder(assignment, configuration.getDaysBefore());
    }
}
