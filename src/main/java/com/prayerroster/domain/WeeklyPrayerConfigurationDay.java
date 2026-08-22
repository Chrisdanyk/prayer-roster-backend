package com.prayerroster.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.DayOfWeek;

/**
 * One day's setting within a {@link WeeklyPrayerConfiguration} version. Moderation is required
 * every day unconditionally (hard constraint, not configurable per day - see
 * docs/phase1-architecture.md section 7), so there is deliberately no {@code requiresModerator}
 * column here, only whether that day also requires a preacher.
 */
@Entity
@Table(name = "weekly_prayer_configuration_day")
public class WeeklyPrayerConfigurationDay implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator", sequenceName = "sequence_generator", allocationSize = 50)
    @Column(name = "id")
    private Long id;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "configuration_id", nullable = false)
    private WeeklyPrayerConfiguration configuration;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 20, nullable = false)
    private DayOfWeek dayOfWeek;

    @NotNull
    @Column(name = "requires_preacher", nullable = false)
    private boolean requiresPreacher;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public WeeklyPrayerConfiguration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(WeeklyPrayerConfiguration configuration) {
        this.configuration = configuration;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WeeklyPrayerConfigurationDay other)) {
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
        return "WeeklyPrayerConfigurationDay{" + "id=" + id + ", dayOfWeek=" + dayOfWeek + ", requiresPreacher=" + requiresPreacher + '}';
    }
}
