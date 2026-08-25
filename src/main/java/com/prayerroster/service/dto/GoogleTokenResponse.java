package com.prayerroster.service.dto;

/** What the caller receives after a successful authorization-code exchange. */
public record GoogleTokenResponse(String idToken, long expiresIn) {}
