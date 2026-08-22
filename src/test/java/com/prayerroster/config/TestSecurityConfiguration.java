package com.prayerroster.config;

import static org.mockito.Mockito.mock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Allows running tests without a real Google issuer: the real {@link SecurityConfiguration#jwtDecoder()}
 * bean (which would otherwise call out to Google's JWKS endpoint) is replaced by a mock that
 * individual tests configure per case.
 */
@TestConfiguration
public class TestSecurityConfiguration {

    @Bean
    JwtDecoder jwtDecoder() {
        return mock(JwtDecoder.class);
    }
}
