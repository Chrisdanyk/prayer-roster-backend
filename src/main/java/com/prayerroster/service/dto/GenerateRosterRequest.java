package com.prayerroster.service.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record GenerateRosterRequest(@NotNull LocalDate from, @NotNull LocalDate to) {}
