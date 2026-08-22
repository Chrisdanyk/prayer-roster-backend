package com.prayerroster.web.rest;

import static com.prayerroster.test.util.OAuth2TestUtil.googleJwt;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountResourceTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AccountResource()).build();
    }

    @Test
    void getAccount_returnsMappedUserVmForJwtPrincipal() throws Exception {
        var authorities = List.of(new SimpleGrantedAuthority("PERM_ROSTER_GENERATE"));
        var token = new JwtAuthenticationToken(googleJwt("108234567890123456789", "jean@example.com"), authorities);

        mockMvc
            .perform(get("/api/account").principal(token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.login").value("108234567890123456789"))
            .andExpect(jsonPath("$.authorities[0]").value("PERM_ROSTER_GENERATE"))
            .andExpect(jsonPath("$.email").value("jean@example.com"));
    }

    @Test
    void getAccount_throwsWhenNoPrincipal() {
        assertThatThrownBy(() -> mockMvc.perform(get("/api/account")))
            .isInstanceOf(ServletException.class)
            .hasMessageContaining("User could not be found");
    }

    @Test
    void getAccount_throwsForNonJwtAuthenticationToken() {
        var token = new UsernamePasswordAuthenticationToken("jean", "pw", Collections.emptyList());

        assertThatThrownBy(() -> mockMvc.perform(get("/api/account").principal(token)))
            .isInstanceOf(ServletException.class)
            .hasMessageContaining("is not a JWT");
    }

    @Test
    void isAuthenticated_returns204WhenPrincipalPresent() throws Exception {
        var token = new JwtAuthenticationToken(googleJwt("108", "jean@example.com"));

        mockMvc.perform(get("/api/authenticate").principal(token)).andExpect(status().isNoContent());
    }

    @Test
    void isAuthenticated_returns401WhenNoPrincipal() throws Exception {
        mockMvc.perform(get("/api/authenticate")).andExpect(status().isUnauthorized());
    }
}
