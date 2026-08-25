package com.prayerroster.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.stereotype.Service;

/**
 * Holds a freshly-minted ID token for the few milliseconds between the Google callback redirecting
 * the browser and the SPA redeeming it. The handoff is opaque and single-use, so the token itself
 * never travels in a URL - which is the entire point: a token in a query string reaches browser
 * history, Referer headers and access logs. See docs/phase1-architecture.md section 10.
 */
@Service
public class HandoffStore {

    private static final String ENTITY_NAME = "authentication";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Cache<String, GoogleTokenResponse> tokensByHandoff = Caffeine.newBuilder()
        .expireAfterWrite(TTL)
        .maximumSize(10_000)
        .build();

    public String issue(GoogleTokenResponse token) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String handoff = encoder.encodeToString(bytes);
        tokensByHandoff.put(handoff, token);
        return handoff;
    }

    /** Single use: removed atomically, so a replayed handoff fails. */
    public GoogleTokenResponse redeem(String handoff) {
        GoogleTokenResponse token = handoff == null ? null : tokensByHandoff.asMap().remove(handoff);
        if (token == null) {
            throw new BadRequestAlertException("Unknown, expired, or already used handoff", ENTITY_NAME, "invalidHandoff");
        }
        return token;
    }
}
