package com.prayerroster.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

/**
 * One role slot (moderator or preacher) on a {@link PrayerSession}. Rows only exist when required -
 * a moderation-only day never gets a PREACHER row at all, which structurally guarantees "no
 * preacher on moderation-only days" without needing a runtime constraint for it (see
 * docs/phase1-architecture.md section 2/11).
 * <p>
 * The Timefold {@code @PlanningEntity}: {@code user} is the {@code @PlanningVariable} the solver
 * fills in, {@code locked} is the {@code @PlanningPin} - true means Timefold must not move this
 * assignment, which from Sprint 6 on is how reschedule solves leave unaffected assignments alone
 * (a structural guarantee, not a soft penalty).
 */
@Entity
@Table(name = "prayer_assignment")
@PlanningEntity
public class PrayerAssignment extends AbstractAuditingEntity<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator", sequenceName = "sequence_generator", allocationSize = 50)
    @Column(name = "id")
    @PlanningId
    private Long id;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private PrayerSession session;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private PrayerAssignmentRole role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @PlanningVariable(valueRangeProviderRefs = "userRange")
    private User user;

    @NotNull
    @Column(name = "locked", nullable = false)
    @PlanningPin
    private boolean locked = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generation_id")
    private RosterGeneration generation;

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PrayerSession getSession() {
        return session;
    }

    public void setSession(PrayerSession session) {
        this.session = session;
    }

    public PrayerAssignmentRole getRole() {
        return role;
    }

    public void setRole(PrayerAssignmentRole role) {
        this.role = role;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public RosterGeneration getGeneration() {
        return generation;
    }

    public void setGeneration(RosterGeneration generation) {
        this.generation = generation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PrayerAssignment other)) {
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
        return "PrayerAssignment{" + "id=" + id + ", role=" + role + '}';
    }
}
