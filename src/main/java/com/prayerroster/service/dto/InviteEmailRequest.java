package com.prayerroster.service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteEmailRequest(@NotBlank @Email @Size(max = 254) String email) {}
