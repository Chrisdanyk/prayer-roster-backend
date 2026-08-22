package com.prayerroster.service.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateNotificationPreferenceRequest(@NotNull Boolean emailEnabled) {}
