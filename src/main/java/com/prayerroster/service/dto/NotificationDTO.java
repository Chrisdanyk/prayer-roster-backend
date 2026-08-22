package com.prayerroster.service.dto;

import com.prayerroster.domain.EmailStatus;
import com.prayerroster.domain.Notification;
import com.prayerroster.domain.NotificationType;
import java.time.Instant;

public record NotificationDTO(
    Long id,
    NotificationType type,
    String subject,
    String body,
    boolean read,
    Instant readAt,
    EmailStatus emailStatus,
    Instant createdDate
) {
    public static NotificationDTO from(Notification notification, String subject, String body) {
        return new NotificationDTO(
            notification.getId(),
            notification.getType(),
            subject,
            body,
            notification.isRead(),
            notification.getReadAt(),
            notification.getEmailStatus(),
            notification.getCreatedDate()
        );
    }
}
