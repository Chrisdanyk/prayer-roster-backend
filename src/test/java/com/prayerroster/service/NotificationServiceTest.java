package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.domain.Notification;
import com.prayerroster.domain.NotificationType;
import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.domain.User;
import com.prayerroster.repository.NotificationRepository;
import com.prayerroster.service.dto.NotificationDTO;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationTextResolver textResolver;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notificationRepository, textResolver, eventPublisher, new ObjectMapper());
    }

    private static User user(String id, String langKey) {
        User user = new User();
        user.setId(id);
        user.setLangKey(langKey);
        return user;
    }

    private static PrayerAssignment assignment(User user, PrayerAssignmentRole role) {
        PrayerAssignment assignment = new PrayerAssignment();
        assignment.setId(1L);
        assignment.setRole(role);
        assignment.setUser(user);
        PrayerSession session = new PrayerSession();
        session.setId(1L);
        session.setDate(LocalDate.of(2026, 9, 6));
        assignment.setSession(session);
        return assignment;
    }

    private void stubSave() {
        AtomicLong ids = new AtomicLong(1);
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(ids.getAndIncrement());
            return n;
        });
    }

    @Test
    void notifyAssignmentPublished_savesNotificationAndPublishesEvent() {
        stubSave();
        User recipient = user("u1", "fr");
        PrayerAssignment assignment = assignment(recipient, PrayerAssignmentRole.MODERATOR);

        service.notifyAssignmentPublished(assignment);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getRecipient()).isEqualTo(recipient);
        assertThat(saved.getType()).isEqualTo(NotificationType.ASSIGNMENT_PUBLISHED);
        assertThat(saved.getMessageKey()).isEqualTo("notification.assignmentPublished");
        assertThat(saved.getParams()).contains("\"date\":\"2026-09-06\"").contains("\"role\":\"MODERATOR\"");
        assertThat(saved.getRelatedAssignment()).isEqualTo(assignment);
        assertThat(saved.getRelatedSession()).isEqualTo(assignment.getSession());

        ArgumentCaptor<NotificationCreatedEvent> eventCaptor = ArgumentCaptor.forClass(NotificationCreatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().notificationId()).isEqualTo(saved.getId());
    }

    @Test
    void notifyAssignmentRemoved_targetsThePreviousUserNotTheAssignmentsCurrentUser() {
        stubSave();
        User previousUser = user("u1", "fr");
        User currentUser = user("u2", "fr");
        PrayerAssignment assignment = assignment(currentUser, PrayerAssignmentRole.PREACHER);

        service.notifyAssignmentRemoved(previousUser, assignment);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getRecipient()).isEqualTo(previousUser);
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.ASSIGNMENT_REMOVED);
    }

    @Test
    void notifyAssignmentReminder_includesDaysBeforeInParams() {
        stubSave();
        User recipient = user("u1", "fr");
        PrayerAssignment assignment = assignment(recipient, PrayerAssignmentRole.MODERATOR);

        service.notifyAssignmentReminder(assignment, 7);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.ASSIGNMENT_REMINDER);
        assertThat(captor.getValue().getParams()).contains("\"daysBefore\":\"7\"");
    }

    @Test
    void findOwn_resolvesSubjectAndBodyPerRecipientLocale() {
        User recipient = user("u1", "fr");
        Notification notification = new Notification();
        notification.setId(1L);
        notification.setRecipient(recipient);
        notification.setType(NotificationType.ASSIGNMENT_PUBLISHED);
        when(notificationRepository.findByRecipientId(eq("u1"), any())).thenReturn(
            new PageImpl<>(List.of(notification), PageRequest.of(0, 20), 1)
        );
        when(textResolver.resolveSubject(eq(notification), eq(Locale.forLanguageTag("fr")))).thenReturn("Sujet");
        when(textResolver.resolveBody(eq(notification), eq(Locale.forLanguageTag("fr")))).thenReturn("Corps");

        var page = service.findOwn("u1", PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        NotificationDTO dto = page.getContent().get(0);
        assertThat(dto.subject()).isEqualTo("Sujet");
        assertThat(dto.body()).isEqualTo("Corps");
    }

    @Test
    void markRead_setsReadAndReadAt() {
        User recipient = user("u1", "fr");
        Notification notification = new Notification();
        notification.setId(5L);
        notification.setRecipient(recipient);
        notification.setType(NotificationType.ASSIGNMENT_PUBLISHED);
        when(notificationRepository.findByIdAndRecipientId(5L, "u1")).thenReturn(Optional.of(notification));
        when(textResolver.resolveSubject(any(), any())).thenReturn("Sujet");
        when(textResolver.resolveBody(any(), any())).thenReturn("Corps");

        NotificationDTO result = service.markRead("u1", 5L);

        assertThat(result.read()).isTrue();
        assertThat(notification.isRead()).isTrue();
        assertThat(notification.getReadAt()).isNotNull();
    }

    @Test
    void markRead_isIdempotentWhenAlreadyRead() {
        User recipient = user("u1", "fr");
        Notification notification = new Notification();
        notification.setId(5L);
        notification.setRecipient(recipient);
        notification.setType(NotificationType.ASSIGNMENT_PUBLISHED);
        notification.setRead(true);
        var originalReadAt = java.time.Instant.parse("2026-01-01T00:00:00Z");
        notification.setReadAt(originalReadAt);
        when(notificationRepository.findByIdAndRecipientId(5L, "u1")).thenReturn(Optional.of(notification));
        when(textResolver.resolveSubject(any(), any())).thenReturn("Sujet");
        when(textResolver.resolveBody(any(), any())).thenReturn("Corps");

        service.markRead("u1", 5L);

        assertThat(notification.getReadAt()).isEqualTo(originalReadAt);
    }

    @Test
    void markRead_throwsWhenNotOwnedOrMissing() {
        when(notificationRepository.findByIdAndRecipientId(99L, "u1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead("u1", 99L)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void notifyAssignmentPublished_wrapsParamSerializationFailure() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        JsonProcessingException failure = mock(JsonProcessingException.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(failure);
        NotificationService failingService = new NotificationService(notificationRepository, textResolver, eventPublisher, failingMapper);
        PrayerAssignment assignment = assignment(user("u1", "fr"), PrayerAssignmentRole.MODERATOR);

        assertThatThrownBy(() -> failingService.notifyAssignmentPublished(assignment))
            .isInstanceOf(IllegalStateException.class)
            .hasCause(failure);
    }
}
