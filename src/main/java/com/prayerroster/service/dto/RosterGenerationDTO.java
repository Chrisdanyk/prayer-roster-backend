package com.prayerroster.service.dto;

import com.prayerroster.domain.RosterGeneration;
import com.prayerroster.domain.RosterGenerationStatus;
import com.prayerroster.domain.RosterGenerationTrigger;
import java.time.Instant;
import java.time.LocalDate;

/** One immutable audit row per solve attempt - see docs/phase1-architecture.md section 2. */
public record RosterGenerationDTO(
    Long id,
    RosterGenerationTrigger trigger,
    RosterGenerationStatus status,
    LocalDate planningFrom,
    LocalDate planningTo,
    Integer hardScore,
    Integer softScore,
    Boolean feasible,
    Long solverDurationMs,
    String rescheduleReason,
    String errorMessage,
    Instant createdDate,
    String createdBy
) {
    public static RosterGenerationDTO from(RosterGeneration generation) {
        return new RosterGenerationDTO(
            generation.getId(),
            generation.getTrigger(),
            generation.getStatus(),
            generation.getPlanningFrom(),
            generation.getPlanningTo(),
            generation.getHardScore(),
            generation.getSoftScore(),
            generation.getFeasible(),
            generation.getSolverDurationMs(),
            generation.getRescheduleReason(),
            generation.getErrorMessage(),
            generation.getCreatedDate(),
            generation.getCreatedBy()
        );
    }
}
