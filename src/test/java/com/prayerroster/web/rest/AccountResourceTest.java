package com.prayerroster.web.rest;

import static com.prayerroster.test.util.OAuth2TestUtil.googleJwt;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prayerroster.domain.Role;
import com.prayerroster.domain.User;
import com.prayerroster.repository.UserRepository;
import jakarta.servlet.ServletException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AccountResourceTest {

    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;

    private static User member(String roleName, boolean canModerate, boolean canPreach) {
        Role role = new Role();
        role.setName(roleName);
        User user = new User();
        user.setId("108234567890123456789");
        user.setRole(role);
        user.setCanModerate(canModerate);
        user.setCanPreach(canPreach);
        return user;
    }

    @BeforeEach
    void setUp() {
        // lenient: the authenticate/no-principal cases never reach the lookup.
        lenient().when(userRepository.findByIdWithRole(anyString())).thenReturn(Optional.of(member("USER", true, false)));
        mockMvc = MockMvcBuilders.standaloneSetup(new AccountResource(userRepository)).build();
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
    void getAccount_exposesServiceCapabilitiesAndApplicationRole() throws Exception {
        lenient()
            .when(userRepository.findByIdWithRole(anyString()))
            .thenReturn(Optional.of(member("ADMIN", true, true)));
        var token = new JwtAuthenticationToken(googleJwt("108234567890123456789", "jean@example.com"), List.of());

        mockMvc
            .perform(get("/api/account").principal(token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canModerate").value(true))
            .andExpect(jsonPath("$.canPreach").value(true))
            .andExpect(jsonPath("$.roleName").value("ADMIN"));
    }

    @Test
    void getAccount_reportsNoCapabilitiesWhenTheUserRowIsGone() throws Exception {
        lenient().when(userRepository.findByIdWithRole(anyString())).thenReturn(Optional.empty());
        var token = new JwtAuthenticationToken(googleJwt("108234567890123456789", "jean@example.com"), List.of());

        mockMvc
            .perform(get("/api/account").principal(token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canModerate").value(false))
            .andExpect(jsonPath("$.canPreach").value(false))
            .andExpect(jsonPath("$.roleName").doesNotExist());
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
