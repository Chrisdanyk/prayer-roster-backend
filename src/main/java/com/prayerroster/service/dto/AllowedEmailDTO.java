package com.prayerroster.service.dto;

import com.prayerroster.domain.AllowedEmail;
import java.time.Instant;

public record AllowedEmailDTO(Long id, String email, Instant createdDate) {
    public static AllowedEmailDTO from(AllowedEmail allowedEmail) {
        return new AllowedEmailDTO(allowedEmail.getId(), allowedEmail.getEmail(), allowedEmail.getCreatedDate());
    }
}
