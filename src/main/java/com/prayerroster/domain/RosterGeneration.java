package com.prayerroster.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * An immutable, append-only audit row for one solve attempt against a {@link Roster} - covers both
 * initial generation and, from Sprint 6 on, rescheduling. {@code triggeredAt}/{@code triggeredBy}
 * are deliberately not separate columns - they're exactly {@code createdDate}/{@code createdBy}
 * from {@link AbstractAuditingEntity}, so reusing those avoids duplicating the same fact twice.
 * {@code hardScore}/{@code softScore}/{@code feasible}/{@code solverDurationMs} stay unpopulated
 * until Sprint 5 wires in the actual solver; Sprint 4 only ever produces {@code COMPLETED} or
 * {@code FAILED} from structural validation, never {@code INFEASIBLE} (that's a solver outcome).
 */
@Entity
@Table(name = "roster_generation")
public class RosterGeneration extends AbstractAuditingEntity<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator", sequenceName = "sequence_generator", allocationSize = 50)
    @Column(name = "id")
    private Long id;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "roster_id", nullable = false)
    private Roster roster;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", length = 30, nullable = false)
    private RosterGenerationTrigger trigger;

    @NotNull
    @Column(name = "planning_from", nullable = false)
    private LocalDate planningFrom;

    @NotNull
    @Column(name = "planning_to", nullable = false)
    private LocalDate planningTo;

    @Column(name = "solver_duration_ms")
    private Long solverDurationMs;

    @Column(name = "hard_score")
    private Integer hardScore;

    @Column(name = "soft_score")
    private Integer softScore;

    @Column(name = "feasible")
    private Boolean feasible;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RosterGenerationStatus status;

    @NotNull
    @Column(name = "regenerated", nullable = false)
    private boolean regenerated = false;

    @Column(name = "reschedule_reason", length = 500)
    private String rescheduleReason;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Roster getRoster() {
        return roster;
    }

    public void setRoster(Roster roster) {
        this.roster = roster;
    }

    public RosterGenerationTrigger getTrigger() {
        return trigger;
    }

    public void setTrigger(RosterGenerationTrigger trigger) {
        this.trigger = trigger;
    }

    public LocalDate getPlanningFrom() {
        return planningFrom;
    }

    public void setPlanningFrom(LocalDate planningFrom) {
        this.planningFrom = planningFrom;
    }

    public LocalDate getPlanningTo() {
        return planningTo;
    }

    public void setPlanningTo(LocalDate planningTo) {
        this.planningTo = planningTo;
    }

    public Long getSolverDurationMs() {
        return solverDurationMs;
    }

    public void setSolverDurationMs(Long solverDurationMs) {
        this.solverDurationMs = solverDurationMs;
    }

    public Integer getHardScore() {
        return hardScore;
    }

    public void setHardScore(Integer hardScore) {
        this.hardScore = hardScore;
    }

    public Integer getSoftScore() {
        return softScore;
    }

    public void setSoftScore(Integer softScore) {
        this.softScore = softScore;
    }

    public Boolean getFeasible() {
        return feasible;
    }

    public void setFeasible(Boolean feasible) {
        this.feasible = feasible;
    }

    public RosterGenerationStatus getStatus() {
        return status;
    }

    public void setStatus(RosterGenerationStatus status) {
        this.status = status;
    }

    public boolean isRegenerated() {
        return regenerated;
    }

    public void setRegenerated(boolean regenerated) {
        this.regenerated = regenerated;
    }

    public String getRescheduleReason() {
        return rescheduleReason;
    }

    public void setRescheduleReason(String rescheduleReason) {
        this.rescheduleReason = rescheduleReason;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RosterGeneration other)) {
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
        return "RosterGeneration{" + "id=" + id + ", trigger=" + trigger + ", status=" + status + '}';
    }
}
