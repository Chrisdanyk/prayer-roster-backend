package com.prayerroster.service.dto;

import com.prayerroster.domain.NotificationPreference;

public record NotificationPreferenceDTO(boolean emailEnabled) {
    public static NotificationPreferenceDTO from(NotificationPreference preference) {
        return new NotificationPreferenceDTO(preference.isEmailEnabled());
    }
}
