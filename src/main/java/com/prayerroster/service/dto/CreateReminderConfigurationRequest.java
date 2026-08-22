package com.prayerroster.service.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateReminderConfigurationRequest(@NotNull @Positive Integer daysBefore) {}
