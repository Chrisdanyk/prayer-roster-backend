package com.prayerroster.service.dto;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.User;

public record PrayerAssignmentDTO(Long id, PrayerAssignmentRole role, String userId, String userName) {
    public static PrayerAssignmentDTO from(PrayerAssignment assignment) {
        User user = assignment.getUser();
        if (user == null) {
            return new PrayerAssignmentDTO(assignment.getId(), assignment.getRole(), null, null);
        }
        return new PrayerAssignmentDTO(assignment.getId(), assignment.getRole(), user.getId(), fullName(user));
    }

    private static String fullName(User user) {
        String firstName = user.getFirstName() == null ? "" : user.getFirstName();
        String lastName = user.getLastName() == null ? "" : user.getLastName();
        return (firstName + " " + lastName).trim();
    }
}
