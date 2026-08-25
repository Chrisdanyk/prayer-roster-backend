package com.prayerroster.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.stereotype.Service;

/**
 * Holds the {@code state} and PKCE verifier of an in-flight authorization request. Entries expire
 * after five minutes and are removed on first use, so a replayed callback fails - the same
 * short-TTL-cache approach {@code DynamicAuthoritiesService} uses for authorities.
 */
@Service
public class AuthorizationRequestStore {

    private static final String ENTITY_NAME = "authentication";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Cache<String, String> pendingByState = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(10_000).build();
    private final String digestAlgorithm;

    public AuthorizationRequestStore() {
        this("SHA-256");
    }

    /**
     * The algorithm is a parameter only so the "algorithm unavailable" path is reachable from a test.
     * {@code MessageDigest.getInstance} declares a checked exception that cannot occur on a real JVM,
     * and an untestable catch block is uncovered lines, which fails the coverage gate. Production
     * always uses the no-argument constructor.
     */
    AuthorizationRequestStore(String digestAlgorithm) {
        this.digestAlgorithm = digestAlgorithm;
    }

    public PendingAuthorization create() {
        String state = randomToken();
        String codeVerifier = randomToken();
        String codeChallenge = challengeFor(codeVerifier);
        pendingByState.put(state, codeVerifier);
        return new PendingAuthorization(state, codeVerifier, codeChallenge);
    }

    /** Single use: the entry is removed atomically, so a second callback with the same state fails. */
    public String consume(String state) {
        String codeVerifier = state == null ? null : pendingByState.asMap().remove(state);
        if (codeVerifier == null) {
            throw new BadRequestAlertException("Unknown, expired, or already used authorization state", ENTITY_NAME, "invalidState");
        }
        return codeVerifier;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }

    private String challengeFor(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance(digestAlgorithm).digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return encoder.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Digest algorithm unavailable: " + digestAlgorithm, e);
        }
    }

    public record PendingAuthorization(String state, String codeVerifier, String codeChallenge) {}
}
