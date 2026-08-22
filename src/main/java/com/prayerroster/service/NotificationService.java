package com.prayerroster.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.domain.Notification;
import com.prayerroster.domain.NotificationType;
import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.User;
import com.prayerroster.repository.NotificationRepository;
import com.prayerroster.service.dto.NotificationDTO;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates {@link Notification} rows and publishes {@link NotificationCreatedEvent} for the async
 * email side effect (see docs/phase1-architecture.md section 13). Roster generation/publish never
 * blocks on email - {@link EmailNotificationListener} sends after this transaction commits, on its
 * own thread, so a mail provider outage can never fail a roster publish or reschedule.
 */
@Service
@Transactional
public class NotificationService {

    private static final String KEY_ASSIGNMENT_PUBLISHED = "notification.assignmentPublished";
    private static final String KEY_ASSIGNMENT_REMOVED = "notification.assignmentRemoved";
    private static final String KEY_ASSIGNMENT_REMINDER = "notification.assignmentReminder";

    private final NotificationRepository notificationRepository;
    private final NotificationTextResolver textResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public NotificationService(
        NotificationRepository notificationRepository,
        NotificationTextResolver textResolver,
        ApplicationEventPublisher eventPublisher,
        ObjectMapper objectMapper
    ) {
        this.notificationRepository = notificationRepository;
        this.textResolver = textResolver;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    public void notifyAssignmentPublished(PrayerAssignment assignment) {
        create(assignment.getUser(), NotificationType.ASSIGNMENT_PUBLISHED, KEY_ASSIGNMENT_PUBLISHED, paramsFor(assignment, null), assignment);
    }

    public void notifyAssignmentRemoved(User previousUser, PrayerAssignment assignment) {
        create(previousUser, NotificationType.ASSIGNMENT_REMOVED, KEY_ASSIGNMENT_REMOVED, paramsFor(assignment, null), assignment);
    }

    public void notifyAssignmentReminder(PrayerAssignment assignment, int daysBefore) {
        create(
            assignment.getUser(),
            NotificationType.ASSIGNMENT_REMINDER,
            KEY_ASSIGNMENT_REMINDER,
            paramsFor(assignment, daysBefore),
            assignment
        );
    }

    @Transactional(readOnly = true)
    public Page<NotificationDTO> findOwn(String userId, Pageable pageable) {
        return notificationRepository.findByRecipientId(userId, pageable).map(this::toDto);
    }

    public NotificationDTO markRead(String userId, Long id) {
        Notification notification = notificationRepository.findByIdAndRecipientId(id, userId).orElseThrow(() -> new EntityNotFoundException(
            "Notification not found: " + id
        ));
        if (!notification.isRead()) {
            notification.setRead(true);
            notification.setReadAt(Instant.now());
        }
        return toDto(notification);
    }

    private void create(User recipient, NotificationType type, String messageKey, Map<String, String> params, PrayerAssignment assignment) {
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setType(type);
        notification.setMessageKey(messageKey);
        notification.setParams(writeParams(params));
        notification.setRelatedSession(assignment.getSession());
        notification.setRelatedAssignment(assignment);
        Notification saved = notificationRepository.save(notification);
        eventPublisher.publishEvent(new NotificationCreatedEvent(saved.getId()));
    }

    private NotificationDTO toDto(Notification notification) {
        Locale locale = Locale.forLanguageTag(notification.getRecipient().getLangKey());
        return NotificationDTO.from(notification, textResolver.resolveSubject(notification, locale), textResolver.resolveBody(notification, locale));
    }

    private Map<String, String> paramsFor(PrayerAssignment assignment, Integer daysBefore) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("date", assignment.getSession().getDate().toString());
        params.put("role", assignment.getRole().name());
        if (daysBefore != null) {
            params.put("daysBefore", String.valueOf(daysBefore));
        }
        return params;
    }

    private String writeParams(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification params", e);
        }
    }
}
