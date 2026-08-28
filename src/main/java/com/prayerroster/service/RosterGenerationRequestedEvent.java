package com.prayerroster.service;

/**
 * Published once a {@link com.prayerroster.domain.RosterGeneration} row is durably saved with status
 * {@code RUNNING} - {@link RosterSolvingListener} picks this up after commit and runs the actual solve
 * off the request thread (see docs/phase1-architecture.md section 33, and
 * docs/superpowers/specs/2026-08-28-async-roster-operations-design.md).
 */
public record RosterGenerationRequestedEvent(Long generationId) {}
