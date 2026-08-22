package com.prayerroster.service.dto;

import com.prayerroster.domain.ReminderConfiguration;

public record ReminderConfigurationDTO(Long id, Integer daysBefore, boolean active) {
    public static ReminderConfigurationDTO from(ReminderConfiguration configuration) {
        return new ReminderConfigurationDTO(configuration.getId(), configuration.getDaysBefore(), configuration.isActive());
    }
}
