package com.prayerroster.service.dto;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import java.time.LocalDate;

/** An assignment that a proposed unavailability would collide with. */
public record ConflictingAssignmentDTO(LocalDate date, PrayerAssignmentRole role) {
    public static ConflictingAssignmentDTO from(PrayerAssignment assignment) {
        return new ConflictingAssignmentDTO(assignment.getSession().getDate(), assignment.getRole());
    }
}
