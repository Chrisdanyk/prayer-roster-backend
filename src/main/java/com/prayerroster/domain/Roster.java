package com.prayerroster.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The mutable lifecycle container for one planning period (see docs/phase1-architecture.md sections
 * 2 and 11). {@link RosterGeneration} is the separate, immutable audit trail of every solve attempt
 * against this roster. Sprint 4 only ever produces {@link RosterStatus#DRAFT} rosters (no solver
 * yet to auto-publish on); Sprint 5 wires publishing in.
 */
@Entity
@Table(name = "roster")
public class Roster extends AbstractAuditingEntity<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator", sequenceName = "sequence_generator", allocationSize = 50)
    @Column(name = "id")
    private Long id;

    @NotNull
    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @NotNull
    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private RosterStatus status = RosterStatus.DRAFT;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public void setPeriodFrom(LocalDate periodFrom) {
        this.periodFrom = periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public void setPeriodTo(LocalDate periodTo) {
        this.periodTo = periodTo;
    }

    public RosterStatus getStatus() {
        return status;
    }

    public void setStatus(RosterStatus status) {
        this.status = status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Roster other)) {
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
        return "Roster{" + "id=" + id + ", periodFrom=" + periodFrom + ", periodTo=" + periodTo + ", status=" + status + '}';
    }
}
