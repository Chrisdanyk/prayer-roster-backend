package com.prayerroster.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.service.GoogleAuthenticationService;
import com.prayerroster.service.HandoffStore;
import com.prayerroster.service.dto.AuthorizationUrlResponse;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class GoogleAuthenticationResourceTest {

    private static final String STATE_COOKIE = "prayer_roster_auth_state";

    @Mock
    private GoogleAuthenticationService googleAuthenticationService;

    @Mock
    private HandoffStore handoffStore;

    private ApplicationProperties applicationProperties;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        applicationProperties = new ApplicationProperties();
        mockMvc = MockMvcBuilders.standaloneSetup(
            new GoogleAuthenticationResource(googleAuthenticationService, handoffStore, applicationProperties)
        )
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
    }

    @Test
    void getAuthorizationUrl_returnsTheUrlAndState() throws Exception {
        when(googleAuthenticationService.authorizationUrl()).thenReturn(
            new AuthorizationUrlResponse("https://accounts.google.com/o/oauth2/v2/auth?x=1", "state-1")
        );

        mockMvc
            .perform(get("/api/auth/google/url"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("state-1"))
            .andExpect(jsonPath("$.authorizationUrl").value("https://accounts.google.com/o/oauth2/v2/auth?x=1"));
    }

    @Test
    void getAuthorizationUrl_setsAnHttpOnlyStateCookieBoundToTheIssuedState() throws Exception {
        when(googleAuthenticationService.authorizationUrl()).thenReturn(new AuthorizationUrlResponse("https://accounts.google.com", "state-1"));

        String setCookie = mockMvc
            .perform(get("/api/auth/google/url"))
            .andReturn()
            .getResponse()
            .getHeader("Set-Cookie");

        assertThat(setCookie).contains("prayer_roster_auth_state=state-1");
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).contains("Path=/api/auth");
        assertThat(setCookie).contains("Max-Age=300");
        assertThat(setCookie).doesNotContainIgnoringCase("Secure");
    }

    @Test
    void getAuthorizationUrl_marksTheCookieSecureOverHttps() throws Exception {
        when(googleAuthenticationService.authorizationUrl()).thenReturn(new AuthorizationUrlResponse("https://accounts.google.com", "state-1"));

        String setCookie = mockMvc
            .perform(get("/api/auth/google/url").secure(true))
            .andReturn()
            .getResponse()
            .getHeader("Set-Cookie");

        assertThat(setCookie).containsIgnoringCase("Secure");
    }

    @Test
    void callback_returnsTheIdToken() throws Exception {
        when(googleAuthenticationService.completeLogin("code-1", "state-1")).thenReturn(new GoogleTokenResponse("id-token", 3599));

        mockMvc
            .perform(get("/api/auth/google/callback").param("code", "code-1").param("state", "state-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.idToken").value("id-token"))
            .andExpect(jsonPath("$.expiresIn").value(3599));
    }

    @Test
    void callback_clearsTheStateCookieOnSuccess() throws Exception {
        when(googleAuthenticationService.completeLogin("code-1", "state-1")).thenReturn(new GoogleTokenResponse("id-token", 3599));

        String setCookie = mockMvc
            .perform(get("/api/auth/google/callback").param("code", "code-1").param("state", "state-1"))
            .andReturn()
            .getResponse()
            .getHeader("Set-Cookie");

        assertThat(setCookie).contains("prayer_roster_auth_state=");
        assertThat(setCookie).contains("Max-Age=0");
    }

    @Test
    void callback_returns400WhenGoogleReportsAnError() throws Exception {
        mockMvc
            .perform(get("/api/auth/google/callback").param("error", "access_denied").param("state", "state-1"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(googleAuthenticationService);
    }

    @Test
    void callback_returns400WhenTheCodeIsMissing() throws Exception {
        mockMvc.perform(get("/api/auth/google/callback").param("state", "state-1")).andExpect(status().isBadRequest());

        verifyNoInteractions(googleAuthenticationService);
    }

    @Test
    void callback_returns400OnAnInvalidState() throws Exception {
        when(googleAuthenticationService.completeLogin("code-1", "bad")).thenThrow(
            new BadRequestAlertException("invalid", "authentication", "invalidState")
        );

        mockMvc.perform(get("/api/auth/google/callback").param("code", "code-1").param("state", "bad")).andExpect(status().isBadRequest());
    }

    @Test
    void callback_doesNotRequireTheStateCookieWhenNoFrontendIsConfigured() throws Exception {
        when(googleAuthenticationService.completeLogin("code-1", "state-1")).thenReturn(new GoogleTokenResponse("id-token", 3599));

        mockMvc
            .perform(get("/api/auth/google/callback").param("code", "code-1").param("state", "state-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.idToken").value("id-token"));
    }

    @Test
    void callback_returnsJsonWhenNoFrontendIsConfigured() throws Exception {
        when(googleAuthenticationService.completeLogin("code-1", "state-1")).thenReturn(new GoogleTokenResponse("id-token", 3599));

        mockMvc
            .perform(get("/api/auth/google/callback").param("code", "code-1").param("state", "state-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.idToken").value("id-token"));

        verifyNoInteractions(handoffStore);
    }

    @Test
    void callback_returnsJsonWhenFrontendBaseUrlIsBlank() throws Exception {
        applicationProperties.getFrontend().setBaseUrl("");
        when(googleAuthenticationService.completeLogin("code-1", "state-1")).thenReturn(new GoogleTokenResponse("id-token", 3599));

        mockMvc
            .perform(get("/api/auth/google/callback").param("code", "code-1").param("state", "state-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.idToken").value("id-token"));

        verifyNoInteractions(handoffStore);
    }

    @Test
    void callback_redirectsToTheFrontendWithAHandoffWhenConfigured() throws Exception {
        applicationProperties.getFrontend().setBaseUrl("https://app.example.com");
        GoogleTokenResponse token = new GoogleTokenResponse("id-token", 3599);
        when(googleAuthenticationService.completeLogin("code-1", "state-1")).thenReturn(token);
        when(handoffStore.issue(token)).thenReturn("handoff-1");

        mockMvc
            .perform(
                get("/api/auth/google/callback")
                    .param("code", "code-1")
                    .param("state", "state-1")
                    .cookie(new Cookie(STATE_COOKIE, "state-1"))
            )
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://app.example.com/auth/callback?handoff=handoff-1"));
    }

    @Test
    void callback_neverPutsTheTokenInTheRedirectUrl() throws Exception {
        applicationProperties.getFrontend().setBaseUrl("https://app.example.com");
        GoogleTokenResponse token = new GoogleTokenResponse("super-secret-token", 3599);
        when(googleAuthenticationService.completeLogin("code-1", "state-1")).thenReturn(token);
        when(handoffStore.issue(token)).thenReturn("handoff-1");

        String location = mockMvc
            .perform(
                get("/api/auth/google/callback")
                    .param("code", "code-1")
                    .param("state", "state-1")
                    .cookie(new Cookie(STATE_COOKIE, "state-1"))
            )
            .andReturn()
            .getResponse()
            .getHeader("Location");

        assertThat(location).doesNotContain("super-secret-token");
    }

    @Test
    void callback_rejectsAMismatchedStateCookieWhenFrontendIsConfigured() throws Exception {
        applicationProperties.getFrontend().setBaseUrl("https://app.example.com");

        mockMvc
            .perform(
                get("/api/auth/google/callback")
                    .param("code", "code-1")
                    .param("state", "attacker-state")
                    .cookie(new Cookie(STATE_COOKIE, "victim-state"))
            )
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://app.example.com/auth/callback?error=invalidState"));

        verifyNoInteractions(googleAuthenticationService);
    }

    @Test
    void callback_rejectsAMissingStateCookieWhenFrontendIsConfigured() throws Exception {
        applicationProperties.getFrontend().setBaseUrl("https://app.example.com");

        mockMvc
            .perform(get("/api/auth/google/callback").param("code", "code-1").param("state", "attacker-state"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://app.example.com/auth/callback?error=invalidState"));

        verifyNoInteractions(googleAuthenticationService);
    }

    @Test
    void callback_redirectsToTheFrontendOnAuthorizationDeniedWhenConfigured() throws Exception {
        applicationProperties.getFrontend().setBaseUrl("https://app.example.com");

        mockMvc
            .perform(get("/api/auth/google/callback").param("error", "access_denied").param("state", "state-1"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://app.example.com/auth/callback?error=authorizationDenied"));

        verifyNoInteractions(googleAuthenticationService);
    }

    @Test
    void callback_redirectsToTheFrontendOnMissingCodeWhenConfigured() throws Exception {
        applicationProperties.getFrontend().setBaseUrl("https://app.example.com");

        mockMvc
            .perform(get("/api/auth/google/callback").param("state", "state-1"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://app.example.com/auth/callback?error=missingCode"));

        verifyNoInteractions(googleAuthenticationService);
    }

    @Test
    void callback_redirectsToTheFrontendWhenTheUnderlyingStateIsUnknownOrExpired() throws Exception {
        applicationProperties.getFrontend().setBaseUrl("https://app.example.com");
        when(googleAuthenticationService.completeLogin("code-1", "state-1")).thenThrow(
            new BadRequestAlertException("invalid", "authentication", "invalidState")
        );

        mockMvc
            .perform(
                get("/api/auth/google/callback")
                    .param("code", "code-1")
                    .param("state", "state-1")
                    .cookie(new Cookie(STATE_COOKIE, "state-1"))
            )
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://app.example.com/auth/callback?error=invalidState"));
    }

    @Test
    void exchange_redeemsAHandoffForTheToken() throws Exception {
        when(handoffStore.redeem("handoff-1")).thenReturn(new GoogleTokenResponse("id-token", 3599));

        mockMvc
            .perform(
                post("/api/auth/exchange")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"handoff\":\"handoff-1\"}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.idToken").value("id-token"));
    }

    @Test
    void exchange_returns400OnAnInvalidHandoff() throws Exception {
        when(handoffStore.redeem("bad")).thenThrow(new BadRequestAlertException("invalid", "authentication", "invalidHandoff"));

        mockMvc
            .perform(post("/api/auth/exchange").contentType(MediaType.APPLICATION_JSON).content("{\"handoff\":\"bad\"}"))
            .andExpect(status().isBadRequest());
    }
}
