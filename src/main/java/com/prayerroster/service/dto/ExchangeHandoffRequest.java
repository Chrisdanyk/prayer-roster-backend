package com.prayerroster.service.dto;

import jakarta.validation.constraints.NotBlank;

public record ExchangeHandoffRequest(@NotBlank String handoff) {}
