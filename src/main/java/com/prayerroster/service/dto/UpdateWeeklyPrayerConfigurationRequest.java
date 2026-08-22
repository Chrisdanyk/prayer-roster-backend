package com.prayerroster.service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateWeeklyPrayerConfigurationRequest(
    @NotNull @Size(min = 7, max = 7) @Valid List<WeeklyPrayerConfigurationDayDTO> days
) {}
