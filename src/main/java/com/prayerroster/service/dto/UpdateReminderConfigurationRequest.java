package com.prayerroster.service.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateReminderConfigurationRequest(@NotNull Boolean active) {}
