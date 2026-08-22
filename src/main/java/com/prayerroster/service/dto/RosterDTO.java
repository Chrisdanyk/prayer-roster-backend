package com.prayerroster.service.dto;

import com.prayerroster.domain.Roster;
import com.prayerroster.domain.RosterStatus;
import java.time.Instant;
import java.time.LocalDate;

public record RosterDTO(Long id, LocalDate periodFrom, LocalDate periodTo, RosterStatus status, Instant publishedAt) {
    public static RosterDTO from(Roster roster) {
        return new RosterDTO(roster.getId(), roster.getPeriodFrom(), roster.getPeriodTo(), roster.getStatus(), roster.getPublishedAt());
    }
}
