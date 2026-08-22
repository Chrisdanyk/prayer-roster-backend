package com.prayerroster.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Test class for the {@link SecurityUtils} utility class.
 */
class SecurityUtilsUnitTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt googleJwt(String sub) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", sub);
        claims.put("email", sub + "@example.com");
        return Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claims(c -> c.putAll(claims))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
    }

    @Test
    void testGetCurrentUserLogin() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken("admin", "admin"));
        SecurityContextHolder.setContext(securityContext);
        Optional<String> login = SecurityUtils.getCurrentUserLogin();
        assertThat(login).contains("admin");
    }

    @Test
    void testGetCurrentUserLoginForJwt() {
        // The principal name comes from DynamicJwtAuthenticationConverter (the sub claim), not any
        // claim read directly off the token here - Google issues no preferred_username claim.
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new JwtAuthenticationToken(googleJwt("108234567890123456789"), Collections.emptyList()));
        SecurityContextHolder.setContext(securityContext);

        Optional<String> login = SecurityUtils.getCurrentUserLogin();

        assertThat(login).contains("108234567890123456789");
    }

    @Test
    void testGetCurrentUserLogin_emptyWhenNoAuthentication() {
        assertThat(SecurityUtils.getCurrentUserLogin()).isEmpty();
    }

    @Test
    void testGetCurrentUserLoginForUserDetailsPrincipal() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        var userDetails = new org.springframework.security.core.userdetails.User("admin2", "password", Collections.emptyList());
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(userDetails, "password"));
        SecurityContextHolder.setContext(securityContext);

        assertThat(SecurityUtils.getCurrentUserLogin()).contains("admin2");
    }

    @Test
    void testGetCurrentUserLogin_emptyWhenPrincipalIsUnrecognizedType() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
            new org.springframework.security.authentication.TestingAuthenticationToken(42, "credentials")
        );
        SecurityContextHolder.setContext(securityContext);

        assertThat(SecurityUtils.getCurrentUserLogin()).isEmpty();
    }

    @Test
    void testIsAuthenticated() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken("admin", "admin"));
        SecurityContextHolder.setContext(securityContext);
        boolean isAuthenticated = SecurityUtils.isAuthenticated();
        assertThat(isAuthenticated).isTrue();
    }

    @Test
    void testIsAuthenticated_falseWhenNoAuthentication() {
        assertThat(SecurityUtils.isAuthenticated()).isFalse();
    }

    @Test
    void testHasCurrentUserAnyOfAuthorities_falseWhenNoAuthentication() {
        assertThat(SecurityUtils.hasCurrentUserAnyOfAuthorities(AuthoritiesConstants.ADMIN)).isFalse();
    }

    @Test
    void testAnonymousIsNotAuthenticated() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        var authorities = Collections.singletonList(new SimpleGrantedAuthority(
            AuthoritiesConstants.ANONYMOUS
        ));
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken("anonymous", "anonymous", authorities));
        SecurityContextHolder.setContext(securityContext);
        boolean isAuthenticated = SecurityUtils.isAuthenticated();
        assertThat(isAuthenticated).isFalse();
    }

    @Test
    void testHasCurrentUserThisAuthority() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        var authorities = Collections.singletonList(new SimpleGrantedAuthority(
            AuthoritiesConstants.USER
        ));
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken("anonymous", "anonymous", authorities));
        SecurityContextHolder.setContext(securityContext);

        assertThat(SecurityUtils.hasCurrentUserThisAuthority(AuthoritiesConstants.USER)).isTrue();
        assertThat(SecurityUtils.hasCurrentUserThisAuthority(AuthoritiesConstants.ADMIN)).isFalse();
    }

    @Test
    void testHasCurrentUserThisAuthorityForJwt() {
        // Proves getAuthorities() reads the GrantedAuthoritys DynamicJwtAuthenticationConverter set
        // on the token directly, rather than re-deriving anything from JWT claims (Google's ID
        // tokens carry no authorization data, so re-deriving would always find nothing).
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        var authorities = Collections.singletonList(new SimpleGrantedAuthority(
            PermissionAuthorities.of("ROSTER_GENERATE")
        ));
        securityContext.setAuthentication(new JwtAuthenticationToken(googleJwt("108"), authorities));
        SecurityContextHolder.setContext(securityContext);

        assertThat(SecurityUtils.hasCurrentUserThisAuthority(PermissionAuthorities.of("ROSTER_GENERATE"))).isTrue();
        assertThat(SecurityUtils.hasCurrentUserThisAuthority(PermissionAuthorities.of("ROSTER_REGENERATE"))).isFalse();
    }

    @Test
    void testHasCurrentUserAnyOfAuthorities() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        var authorities = Collections.singletonList(new SimpleGrantedAuthority(
            AuthoritiesConstants.USER
        ));
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken("anonymous", "anonymous", authorities));
        SecurityContextHolder.setContext(securityContext);

        assertThat(SecurityUtils.hasCurrentUserAnyOfAuthorities(AuthoritiesConstants.USER, AuthoritiesConstants.ADMIN)).isTrue();
        assertThat(SecurityUtils.hasCurrentUserAnyOfAuthorities(AuthoritiesConstants.ANONYMOUS, AuthoritiesConstants.ADMIN)).isFalse();
    }

    @Test
    void testHasCurrentUserNoneOfAuthorities() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        var authorities = Collections.singletonList(new SimpleGrantedAuthority(
            AuthoritiesConstants.USER
        ));
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken("anonymous", "anonymous", authorities));
        SecurityContextHolder.setContext(securityContext);

        assertThat(SecurityUtils.hasCurrentUserNoneOfAuthorities(AuthoritiesConstants.USER, AuthoritiesConstants.ADMIN)).isFalse();
        assertThat(SecurityUtils.hasCurrentUserNoneOfAuthorities(AuthoritiesConstants.ANONYMOUS, AuthoritiesConstants.ADMIN)).isTrue();
    }

    @Test
    void testExtractDetailsFromTokenAttributes_fullGoogleClaims() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("email_verified", true);
        attributes.put("family_name", "Dupont");
        attributes.put("picture", "https://example.com/photo.jpg");
        attributes.put("given_name", "Jean");
        attributes.put("email", "jean.dupont@example.com");
        attributes.put("locale", "fr_FR");

        Map<String, Object> details = SecurityUtils.extractDetailsFromTokenAttributes(attributes);

        assertThat(details)
            .containsEntry("activated", true)
            .containsEntry("lastName", "Dupont")
            .containsEntry("imageUrl", "https://example.com/photo.jpg")
            .containsEntry("firstName", "Jean")
            .containsEntry("email", "jean.dupont@example.com")
            .containsEntry("langKey", "fr");
    }

    @Test
    void testExtractDetailsFromTokenAttributes_fallsBackToNameSubAndDefaultLangKey() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("uid", "internal-id-1");
        attributes.put("name", "Marie Curie");
        attributes.put("sub", "108234567890");

        Map<String, Object> details = SecurityUtils.extractDetailsFromTokenAttributes(attributes);

        assertThat(details)
            .containsEntry("activated", true)
            .containsEntry("id", "internal-id-1")
            .containsEntry("firstName", "Marie Curie")
            .containsEntry("email", "108234567890")
            .containsEntry("langKey", com.prayerroster.config.Constants.DEFAULT_LANGUAGE);
    }

    @Test
    void testExtractDetailsFromTokenAttributes_auth0StyleSubWithPipeUsesPreferredUsernameAsEmail() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "auth0|123456");
        attributes.put("preferred_username", "jean.dupont@example.com");

        Map<String, Object> details = SecurityUtils.extractDetailsFromTokenAttributes(attributes);

        assertThat(details).containsEntry("email", "jean.dupont@example.com");
    }

    @Test
    void testExtractDetailsFromTokenAttributes_pipeInSubWithoutPreferredUsernameFallsBackToSub() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "auth0|123456");

        Map<String, Object> details = SecurityUtils.extractDetailsFromTokenAttributes(attributes);

        assertThat(details).containsEntry("email", "auth0|123456");
    }

    @Test
    void testExtractDetailsFromTokenAttributes_pipeInSubWithNonEmailPreferredUsernameFallsBackToSub() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "auth0|123456");
        attributes.put("preferred_username", "jeandupont");

        Map<String, Object> details = SecurityUtils.extractDetailsFromTokenAttributes(attributes);

        assertThat(details).containsEntry("email", "auth0|123456");
    }

    @Test
    void testExtractDetailsFromTokenAttributes_hyphenatedLocaleIsTrimmedToLanguage() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("locale", "fr-CA");

        Map<String, Object> details = SecurityUtils.extractDetailsFromTokenAttributes(attributes);

        assertThat(details).containsEntry("langKey", "fr");
    }

    @Test
    void testExtractDetailsFromTokenAttributes_localeWithoutSeparatorIsUsedAsIs() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("locale", "EN");

        Map<String, Object> details = SecurityUtils.extractDetailsFromTokenAttributes(attributes);

        assertThat(details).containsEntry("langKey", "en");
    }

    @Test
    void testExtractDetailsFromTokenAttributes_explicitLangKeyClaimIsUsedAsIs() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("langKey", "en");
        attributes.put("locale", "fr_FR");

        Map<String, Object> details = SecurityUtils.extractDetailsFromTokenAttributes(attributes);

        assertThat(details).containsEntry("langKey", "en");
    }
}
