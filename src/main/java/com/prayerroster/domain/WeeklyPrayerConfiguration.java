package com.prayerroster.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * A version of the recurring weekly prayer pattern (the template - see
 * docs/phase1-architecture.md section 4-5, "Weekly Configuration vs Generated Sessions"). Exactly
 * one version is "current" at a time, identified by {@code effectiveTo == null}, not a separate
 * redundant boolean flag. Changing the pattern closes the current version
 * ({@code effectiveTo = new version's effectiveFrom - 1 day}) and opens a new one, so historical
 * rosters stay tied to the configuration that was actually in effect when they were generated.
 */
@Entity
@Table(name = "weekly_prayer_configuration")
public class WeeklyPrayerConfiguration extends AbstractAuditingEntity<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator", sequenceName = "sequence_generator", allocationSize = 50)
    @Column(name = "id")
    private Long id;

    @NotNull
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** {@code null} means this is the current version. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @OneToMany(mappedBy = "configuration", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<WeeklyPrayerConfigurationDay> days = new HashSet<>();

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public Set<WeeklyPrayerConfigurationDay> getDays() {
        return days;
    }

    public void setDays(Set<WeeklyPrayerConfigurationDay> days) {
        this.days = days;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WeeklyPrayerConfiguration other)) {
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
        return "WeeklyPrayerConfiguration{" + "id=" + id + ", effectiveFrom=" + effectiveFrom + ", effectiveTo=" + effectiveTo + '}';
    }
}
