package com.prayerroster.service.dto;

import com.prayerroster.domain.WeeklyPrayerConfiguration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public record WeeklyPrayerConfigurationDTO(
    Long id,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    List<WeeklyPrayerConfigurationDayDTO> days
) {
    private static final List<DayOfWeek> WEEK_ORDER = List.of(DayOfWeek.values());

    public static WeeklyPrayerConfigurationDTO from(WeeklyPrayerConfiguration configuration) {
        List<WeeklyPrayerConfigurationDayDTO> days = configuration
            .getDays()
            .stream()
            .sorted(Comparator.comparingInt(d -> WEEK_ORDER.indexOf(d.getDayOfWeek())))
            .map(d -> new WeeklyPrayerConfigurationDayDTO(d.getDayOfWeek(), d.isRequiresPreacher()))
            .toList();
        return new WeeklyPrayerConfigurationDTO(configuration.getId(), configuration.getEffectiveFrom(), configuration.getEffectiveTo(), days);
    }
}
