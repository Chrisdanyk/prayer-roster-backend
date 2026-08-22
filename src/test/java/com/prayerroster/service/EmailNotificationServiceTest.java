package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.EmailStatus;
import com.prayerroster.domain.Notification;
import com.prayerroster.domain.NotificationPreference;
import com.prayerroster.domain.NotificationType;
import com.prayerroster.domain.User;
import com.prayerroster.repository.NotificationPreferenceRepository;
import com.prayerroster.repository.NotificationRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationPreferenceRepository notificationPreferenceRepository;

    @Mock
    private NotificationTextResolver textResolver;

    @Mock
    private MailService mailService;

    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailNotificationService(notificationRepository, notificationPreferenceRepository, textResolver, mailService);
    }

    private static Notification notification(Long id, EmailStatus status, int retryCount) {
        User recipient = new User();
        recipient.setId("u1");
        recipient.setEmail("jean@example.com");
        recipient.setLangKey("fr");
        Notification notification = new Notification();
        notification.setId(id);
        notification.setRecipient(recipient);
        notification.setType(NotificationType.ASSIGNMENT_PUBLISHED);
        notification.setMessageKey("notification.assignmentPublished");
        notification.setEmailStatus(status);
        notification.setRetryCount(retryCount);
        return notification;
    }

    @Test
    void sendForNotification_sendsAndMarksSentWhenPreferenceEnabled() {
        Notification notification = notification(1L, EmailStatus.PENDING, 0);
        when(notificationRepository.findByIdWithRecipient(1L)).thenReturn(Optional.of(notification));
        when(notificationPreferenceRepository.findByUserId("u1")).thenReturn(Optional.empty());
        when(textResolver.resolveSubject(eq(notification), eq(Locale.forLanguageTag("fr")))).thenReturn("Sujet");
        when(textResolver.resolveBody(eq(notification), eq(Locale.forLanguageTag("fr")))).thenReturn("Corps");

        service.sendForNotification(1L);

        verify(mailService).sendEmail("jean@example.com", "Sujet", "Corps");
        assertThat(notification.getEmailStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(notification.getEmailSentAt()).isNotNull();
    }

    @Test
    void sendForNotification_skipsWhenPreferenceDisabled() {
        Notification notification = notification(1L, EmailStatus.PENDING, 0);
        NotificationPreference preference = new NotificationPreference();
        preference.setEmailEnabled(false);
        when(notificationRepository.findByIdWithRecipient(1L)).thenReturn(Optional.of(notification));
        when(notificationPreferenceRepository.findByUserId("u1")).thenReturn(Optional.of(preference));

        service.sendForNotification(1L);

        assertThat(notification.getEmailStatus()).isEqualTo(EmailStatus.SKIPPED);
        verify(mailService, never()).sendEmail(any(), any(), any());
    }

    @Test
    void sendForNotification_recordsFailureAndIncrementsRetryCountOnSendError() {
        Notification notification = notification(1L, EmailStatus.PENDING, 0);
        when(notificationRepository.findByIdWithRecipient(1L)).thenReturn(Optional.of(notification));
        when(notificationPreferenceRepository.findByUserId("u1")).thenReturn(Optional.empty());
        when(textResolver.resolveSubject(any(), any())).thenReturn("Sujet");
        when(textResolver.resolveBody(any(), any())).thenReturn("Corps");
        doThrow(new MailSendException("smtp down")).when(mailService).sendEmail(any(), any(), any());

        service.sendForNotification(1L);

        assertThat(notification.getEmailStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(notification.getRetryCount()).isEqualTo(1);
    }

    @Test
    void sendForNotification_doesNothingWhenNotFound() {
        when(notificationRepository.findByIdWithRecipient(99L)).thenReturn(Optional.empty());

        service.sendForNotification(99L);

        verify(mailService, never()).sendEmail(any(), any(), any());
    }

    @Test
    void sendForNotification_doesNothingWhenAlreadyHandled() {
        Notification notification = notification(1L, EmailStatus.SENT, 0);
        when(notificationRepository.findByIdWithRecipient(1L)).thenReturn(Optional.of(notification));

        service.sendForNotification(1L);

        verify(mailService, never()).sendEmail(any(), any(), any());
        verify(notificationPreferenceRepository, never()).findByUserId(any());
    }

    @Test
    void retryFailedEmails_reattemptsEveryRetryableNotification() {
        Notification first = notification(1L, EmailStatus.FAILED, 1);
        Notification second = notification(2L, EmailStatus.FAILED, 2);
        when(notificationRepository.findRetryableByEmailStatus(EmailStatus.FAILED, EmailNotificationService.MAX_RETRIES)).thenReturn(
            List.of(first, second)
        );
        when(textResolver.resolveSubject(any(), any())).thenReturn("Sujet");
        when(textResolver.resolveBody(any(), any())).thenReturn("Corps");

        service.retryFailedEmails();

        verify(mailService, times(2)).sendEmail(eq("jean@example.com"), eq("Sujet"), eq("Corps"));
        assertThat(first.getEmailStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(second.getEmailStatus()).isEqualTo(EmailStatus.SENT);
    }
}
