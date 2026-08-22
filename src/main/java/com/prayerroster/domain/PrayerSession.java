package com.prayerroster.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * One actual dated prayer service, generated from the {@link WeeklyPrayerConfiguration} that was
 * current at generation time. {@code requiresPreacher} is snapshotted at generation - a later
 * config change never retroactively alters an already-generated session (see
 * docs/phase1-architecture.md section 4-5). There is deliberately no {@code requiresModerator}
 * column: moderation is unconditionally required every day (hard constraint), so storing a column
 * that would always be {@code true} would be redundant.
 */
@Entity
@Table(name = "prayer_session")
public class PrayerSession extends AbstractAuditingEntity<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator", sequenceName = "sequence_generator", allocationSize = 50)
    @Column(name = "id")
    private Long id;

    @NotNull
    @Column(name = "date", nullable = false, unique = true)
    private LocalDate date;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 20, nullable = false)
    private DayOfWeek dayOfWeek;

    @NotNull
    @Column(name = "requires_preacher", nullable = false)
    private boolean requiresPreacher;

    @NotNull
    @Column(name = "requires_rescheduling", nullable = false)
    private boolean requiresRescheduling = false;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "roster_id", nullable = false)
    private Roster roster;

    @OneToMany(mappedBy = "session", fetch = FetchType.LAZY)
    private Set<PrayerAssignment> assignments = new HashSet<>();

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(DayOfWeek dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public boolean isRequiresPreacher() {
        return requiresPreacher;
    }

    public void setRequiresPreacher(boolean requiresPreacher) {
        this.requiresPreacher = requiresPreacher;
    }

    public boolean isRequiresRescheduling() {
        return requiresRescheduling;
    }

    public void setRequiresRescheduling(boolean requiresRescheduling) {
        this.requiresRescheduling = requiresRescheduling;
    }

    public Roster getRoster() {
        return roster;
    }

    public void setRoster(Roster roster) {
        this.roster = roster;
    }

    public Set<PrayerAssignment> getAssignments() {
        return assignments;
    }

    public void setAssignments(Set<PrayerAssignment> assignments) {
        this.assignments = assignments;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PrayerSession other)) {
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
        return "PrayerSession{" + "id=" + id + ", date=" + date + ", requiresPreacher=" + requiresPreacher + '}';
    }
}
