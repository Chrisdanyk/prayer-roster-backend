package com.prayerroster.service.dto;

import com.prayerroster.domain.UserAvailability;
import com.prayerroster.domain.UserAvailabilityStatus;
import java.time.LocalDate;

public record UserAvailabilityDTO(Long id, LocalDate startDate, LocalDate endDate, String reason, UserAvailabilityStatus status) {
    public static UserAvailabilityDTO from(UserAvailability availability) {
        return new UserAvailabilityDTO(
            availability.getId(),
            availability.getStartDate(),
            availability.getEndDate(),
            availability.getReason(),
            availability.getStatus()
        );
    }
}
