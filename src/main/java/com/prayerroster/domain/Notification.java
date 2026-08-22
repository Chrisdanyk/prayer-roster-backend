package com.prayerroster.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * A single in-app notification, with email delivery tracked on the same row rather than a separate
 * email-log table (see docs/phase1-architecture.md section 13). {@code messageKey}/{@code params}
 * are resolved to text at read time in the recipient's current locale - never pre-rendered - so a
 * later {@code langKey} change is always reflected correctly. {@code params} is a small JSON object
 * of locale-independent raw values (an ISO date, a role name, ...); see {@code
 * NotificationTextResolver} for how it becomes a final localized string.
 */
@Entity
@Table(name = "notification")
public class Notification extends AbstractAuditingEntity<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator", sequenceName = "sequence_generator", allocationSize = 50)
    @Column(name = "id")
    private Long id;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, nullable = false)
    private NotificationType type;

    @NotNull
    @Size(max = 100)
    @Column(name = "message_key", length = 100, nullable = false)
    private String messageKey;

    @Column(name = "params", columnDefinition = "text")
    private String params;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_session_id")
    private PrayerSession relatedSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_assignment_id")
    private PrayerAssignment relatedAssignment;

    @NotNull
    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "read_at")
    private Instant readAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "email_status", length = 20, nullable = false)
    private EmailStatus emailStatus = EmailStatus.PENDING;

    @Column(name = "email_sent_at")
    private Instant emailSentAt;

    @NotNull
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getRecipient() {
        return recipient;
    }

    public void setRecipient(User recipient) {
        this.recipient = recipient;
    }

    public NotificationType getType() {
        return type;
    }

    public void setType(NotificationType type) {
        this.type = type;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public PrayerSession getRelatedSession() {
        return relatedSession;
    }

    public void setRelatedSession(PrayerSession relatedSession) {
        this.relatedSession = relatedSession;
    }

    public PrayerAssignment getRelatedAssignment() {
        return relatedAssignment;
    }

    public void setRelatedAssignment(PrayerAssignment relatedAssignment) {
        this.relatedAssignment = relatedAssignment;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public EmailStatus getEmailStatus() {
        return emailStatus;
    }

    public void setEmailStatus(EmailStatus emailStatus) {
        this.emailStatus = emailStatus;
    }

    public Instant getEmailSentAt() {
        return emailSentAt;
    }

    public void setEmailSentAt(Instant emailSentAt) {
        this.emailSentAt = emailSentAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Notification other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Notification{" + "id=" + id + ", type=" + type + ", messageKey='" + messageKey + "'" + '}';
    }
}
