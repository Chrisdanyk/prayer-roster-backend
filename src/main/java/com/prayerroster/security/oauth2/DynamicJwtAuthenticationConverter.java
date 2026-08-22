package com.prayerroster.security.oauth2;

import com.prayerroster.security.DynamicAuthoritiesService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Converts a validated Google JWT into an authentication token whose authorities come from our own
 * Role/Permission graph (never from token claims - Google's ID tokens carry no authorization data)
 * and whose principal name is the {@code sub} claim (Google issues no {@code preferred_username}).
 */
@Component
public class DynamicJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final DynamicAuthoritiesService authoritiesService;

    public DynamicJwtAuthenticationConverter(DynamicAuthoritiesService authoritiesService) {
        this.authoritiesService = authoritiesService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        GoogleIdentity identity = GoogleIdentity.fromClaims(jwt.getClaims());
        return new JwtAuthenticationToken(jwt, authoritiesService.resolveAuthorities(identity), identity.sub());
    }
}
