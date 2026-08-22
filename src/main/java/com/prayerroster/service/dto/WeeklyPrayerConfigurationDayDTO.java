package com.prayerroster.service.dto;

import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;

public record WeeklyPrayerConfigurationDayDTO(@NotNull DayOfWeek dayOfWeek, boolean requiresPreacher) {}
