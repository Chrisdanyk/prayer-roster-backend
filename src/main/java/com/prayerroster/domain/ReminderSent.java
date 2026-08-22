package com.prayerroster.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * The reminder idempotency ledger - {@code unique(assignment_id, reminder_configuration_id)} is what
 * makes {@link com.prayerroster.service.ReminderService}'s daily sweep safe to run more than once
 * for the same day without double-sending (see docs/phase1-architecture.md section 13).
 */
@Entity
@Table(name = "reminder_sent")
public class ReminderSent extends AbstractAuditingEntity<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator", sequenceName = "sequence_generator", allocationSize = 50)
    @Column(name = "id")
    private Long id;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false)
    private PrayerAssignment assignment;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "reminder_configuration_id", nullable = false)
    private ReminderConfiguration reminderConfiguration;

    @NotNull
    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PrayerAssignment getAssignment() {
        return assignment;
    }

    public void setAssignment(PrayerAssignment assignment) {
        this.assignment = assignment;
    }

    public ReminderConfiguration getReminderConfiguration() {
        return reminderConfiguration;
    }

    public void setReminderConfiguration(ReminderConfiguration reminderConfiguration) {
        this.reminderConfiguration = reminderConfiguration;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReminderSent other)) {
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
        return "ReminderSent{" + "id=" + id + '}';
    }
}
