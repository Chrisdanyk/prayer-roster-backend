package com.prayerroster.service.dto;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import java.time.LocalDate;

public record UpcomingAssignmentDTO(Long id, LocalDate date, PrayerAssignmentRole role) {
    public static UpcomingAssignmentDTO from(PrayerAssignment assignment) {
        return new UpcomingAssignmentDTO(assignment.getId(), assignment.getSession().getDate(), assignment.getRole());
    }
}
