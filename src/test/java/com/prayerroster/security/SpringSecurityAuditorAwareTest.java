package com.prayerroster.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.prayerroster.config.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SpringSecurityAuditorAwareTest {

    private final SpringSecurityAuditorAware auditorAware = new SpringSecurityAuditorAware();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentAuditor_returnsCurrentUserLoginWhenAuthenticated() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("jean", "pw"));

        assertThat(auditorAware.getCurrentAuditor()).contains("jean");
    }

    @Test
    void getCurrentAuditor_fallsBackToSystemWhenNoAuthentication() {
        assertThat(auditorAware.getCurrentAuditor()).contains(Constants.SYSTEM);
    }
}
