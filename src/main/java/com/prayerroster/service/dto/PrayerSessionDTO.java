package com.prayerroster.service.dto;

import com.prayerroster.domain.PrayerSession;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public record PrayerSessionDTO(
    Long id,
    LocalDate date,
    DayOfWeek dayOfWeek,
    boolean requiresPreacher,
    boolean requiresRescheduling,
    List<PrayerAssignmentDTO> assignments
) {
    public static PrayerSessionDTO from(PrayerSession session) {
        List<PrayerAssignmentDTO> assignments = session
            .getAssignments()
            .stream()
            .map(PrayerAssignmentDTO::from)
            .sorted(Comparator.comparing(PrayerAssignmentDTO::role))
            .toList();
        return new PrayerSessionDTO(
            session.getId(),
            session.getDate(),
            session.getDayOfWeek(),
            session.isRequiresPreacher(),
            session.isRequiresRescheduling(),
            assignments
        );
    }
}
