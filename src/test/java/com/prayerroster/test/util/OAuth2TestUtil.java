package com.prayerroster.test.util;

import com.prayerroster.security.AuthoritiesConstants;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Builds {@link JwtAuthenticationToken}s matching what {@code DynamicJwtAuthenticationConverter}
 * actually produces in production - a Google-shaped {@link Jwt} plus explicit authorities (since
 * real authorities come from our Role/Permission graph, never from token claims).
 */
public class OAuth2TestUtil {

    public static final String TEST_USER_SUB = "108234567890123456789";

    public static Jwt googleJwt(String sub, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", sub);
        claims.put("email", email);
        claims.put("email_verified", true);
        claims.put("given_name", "Jean");
        claims.put("family_name", "Dupont");
        return Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .claims(c -> c.putAll(claims))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
    }

    public static JwtAuthenticationToken authenticationToken(String sub, String email, Collection<String> authorities) {
        List<GrantedAuthority> granted = authorities.stream().map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList();
        return new JwtAuthenticationToken(googleJwt(sub, email), granted, sub);
    }

    /** A token for a user with the JHipster-console-gating authority, matching a SUPER_ADMIN. */
    public static JwtAuthenticationToken testAdminAuthenticationToken() {
        return authenticationToken(TEST_USER_SUB, "jean.dupont@example.com", Collections.singletonList(AuthoritiesConstants.ADMIN));
    }
}
