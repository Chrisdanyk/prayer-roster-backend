package com.prayerroster.security.oauth2;

import java.util.Map;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;

/**
 * The identity claims we rely on from a validated Google ID token. {@code sub} is the stable,
 * never-reassigned subject identifier used as our {@link com.prayerroster.domain.User} primary key.
 */
public record GoogleIdentity(String sub, String email, boolean emailVerified, String firstName, String lastName, String imageUrl) {
    /**
     * {@code email_verified} is read via {@code Boolean.TRUE.equals}, so a missing, null, or
     * non-boolean claim yields {@code false} - deliberately failing closed, since an unverified
     * address is not proof of who the holder is.
     */
    public static GoogleIdentity fromClaims(Map<String, Object> claims) {
        return new GoogleIdentity(
            (String) claims.get(StandardClaimNames.SUB),
            (String) claims.get(StandardClaimNames.EMAIL),
            Boolean.TRUE.equals(claims.get(StandardClaimNames.EMAIL_VERIFIED)),
            (String) claims.get(StandardClaimNames.GIVEN_NAME),
            (String) claims.get(StandardClaimNames.FAMILY_NAME),
            (String) claims.get(StandardClaimNames.PICTURE)
        );
    }
}
