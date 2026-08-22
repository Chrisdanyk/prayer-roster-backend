package com.prayerroster.service.dto;

import jakarta.validation.constraints.Size;

public record RescheduleRequest(@Size(max = 500) String reason) {}
