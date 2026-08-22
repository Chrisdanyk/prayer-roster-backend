package com.prayerroster.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.domain.ReminderConfiguration;
import com.prayerroster.domain.ReminderSent;
import com.prayerroster.repository.PrayerAssignmentRepository;
import com.prayerroster.repository.ReminderConfigurationRepository;
import com.prayerroster.repository.ReminderSentRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class ReminderServiceTest {

    @Mock
    private ReminderConfigurationRepository reminderConfigurationRepository;

    @Mock
    private PrayerAssignmentRepository prayerAssignmentRepository;

    @Mock
    private ReminderSentRepository reminderSentRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private ReminderService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new ReminderService(
            reminderConfigurationRepository,
            prayerAssignmentRepository,
            reminderSentRepository,
            notificationService,
            transactionManager
        );
    }

    private static ReminderConfiguration configuration(Long id, int daysBefore) {
        ReminderConfiguration configuration = new ReminderConfiguration();
        configuration.setId(id);
        configuration.setDaysBefore(daysBefore);
        configuration.setActive(true);
        return configuration;
    }

    private static PrayerAssignment assignment(Long id) {
        PrayerAssignment assignment = new PrayerAssignment();
        assignment.setId(id);
        assignment.setRole(PrayerAssignmentRole.MODERATOR);
        PrayerSession session = new PrayerSession();
        session.setId(id);
        session.setDate(LocalDate.now().plusDays(7));
        assignment.setSession(session);
        return assignment;
    }

    @Test
    void sendDueReminders_recordsLedgerEntryAndNotifiesForEachDueAssignment() {
        ReminderConfiguration configuration = configuration(1L, 7);
        when(reminderConfigurationRepository.findByActiveTrue()).thenReturn(List.of(configuration));
        PrayerAssignment assignment = assignment(1L);
        when(prayerAssignmentRepository.findPublishedAssignmentsForDate(LocalDate.now().plusDays(7))).thenReturn(List.of(assignment));
        when(reminderSentRepository.saveAndFlush(any(ReminderSent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.sendDueReminders();

        verify(reminderSentRepository).saveAndFlush(any(ReminderSent.class));
        verify(notificationService).notifyAssignmentReminder(assignment, 7);
    }

    @Test
    void sendDueReminders_skipsAssignmentsAlreadyRemindedWithoutNotifyingAgain() {
        ReminderConfiguration configuration = configuration(1L, 7);
        when(reminderConfigurationRepository.findByActiveTrue()).thenReturn(List.of(configuration));
        PrayerAssignment assignment = assignment(1L);
        when(prayerAssignmentRepository.findPublishedAssignmentsForDate(LocalDate.now().plusDays(7))).thenReturn(List.of(assignment));
        when(reminderSentRepository.saveAndFlush(any(ReminderSent.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        service.sendDueReminders();

        verify(notificationService, never()).notifyAssignmentReminder(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void sendDueReminders_processesEveryActiveOffsetIndependently() {
        ReminderConfiguration sevenDays = configuration(1L, 7);
        ReminderConfiguration oneDay = configuration(2L, 1);
        when(reminderConfigurationRepository.findByActiveTrue()).thenReturn(List.of(sevenDays, oneDay));
        PrayerAssignment assignmentA = assignment(1L);
        PrayerAssignment assignmentB = assignment(2L);
        when(prayerAssignmentRepository.findPublishedAssignmentsForDate(LocalDate.now().plusDays(7))).thenReturn(List.of(assignmentA));
        when(prayerAssignmentRepository.findPublishedAssignmentsForDate(LocalDate.now().plusDays(1))).thenReturn(List.of(assignmentB));
        when(reminderSentRepository.saveAndFlush(any(ReminderSent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.sendDueReminders();

        verify(notificationService).notifyAssignmentReminder(assignmentA, 7);
        verify(notificationService).notifyAssignmentReminder(assignmentB, 1);
        verify(reminderSentRepository, times(2)).saveAndFlush(any(ReminderSent.class));
    }

    @Test
    void sendDueReminders_doesNothingWhenNoActiveConfigurations() {
        when(reminderConfigurationRepository.findByActiveTrue()).thenReturn(List.of());

        service.sendDueReminders();

        verify(prayerAssignmentRepository, never()).findPublishedAssignmentsForDate(any());
    }
}
