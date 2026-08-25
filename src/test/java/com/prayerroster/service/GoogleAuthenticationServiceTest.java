package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.service.AuthorizationRequestStore.PendingAuthorization;
import com.prayerroster.service.dto.AuthorizationUrlResponse;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GoogleAuthenticationServiceTest {

    @Mock
    private GoogleDiscoveryService discoveryService;

    @Mock
    private AuthorizationRequestStore requestStore;

    @Mock
    private GoogleTokenExchangeService tokenExchangeService;

    private GoogleAuthenticationService service;

    @BeforeEach
    void setUp() {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getGoogle().setClientId("client-id");
        properties.getGoogle().setRedirectUri("https://app.example.com/api/auth/google/callback");
        service = new GoogleAuthenticationService(discoveryService, requestStore, tokenExchangeService, properties);
    }

    @Test
    void authorizationUrl_containsEveryRequiredParameter() {
        when(discoveryService.authorizationEndpoint()).thenReturn("https://accounts.google.com/o/oauth2/v2/auth");
        when(requestStore.create()).thenReturn(new PendingAuthorization("state-1", "verifier-1", "challenge-1"));

        AuthorizationUrlResponse response = service.authorizationUrl();

        assertThat(response.state()).isEqualTo("state-1");
        assertThat(response.authorizationUrl())
            .startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
            .contains("client_id=client-id")
            .contains("response_type=code")
            .contains("state=state-1")
            .contains("code_challenge=challenge-1")
            .contains("code_challenge_method=S256")
            .contains("redirect_uri=https%3A%2F%2Fapp.example.com%2Fapi%2Fauth%2Fgoogle%2Fcallback");
    }

    @Test
    void authorizationUrl_requestsTheOpenIdEmailAndProfileScopes() {
        when(discoveryService.authorizationEndpoint()).thenReturn("https://accounts.google.com/o/oauth2/v2/auth");
        when(requestStore.create()).thenReturn(new PendingAuthorization("state-1", "verifier-1", "challenge-1"));

        assertThat(service.authorizationUrl().authorizationUrl()).contains("scope=openid%20email%20profile");
    }

    @Test
    void completeLogin_consumesTheStateAndExchangesTheCode() {
        when(requestStore.consume("state-1")).thenReturn("verifier-1");
        when(tokenExchangeService.exchange("code-1", "verifier-1")).thenReturn(new GoogleTokenResponse("id-token", 3599));

        GoogleTokenResponse response = service.completeLogin("code-1", "state-1");

        assertThat(response.idToken()).isEqualTo("id-token");
        verify(requestStore).consume("state-1");
    }

    @Test
    void completeLogin_propagatesAnInvalidStateWithoutExchanging() {
        when(requestStore.consume("bad-state")).thenThrow(new BadRequestAlertException("invalid", "authentication", "invalidState"));

        assertThatThrownBy(() -> service.completeLogin("code-1", "bad-state")).isInstanceOf(BadRequestAlertException.class);

        verifyNoInteractions(tokenExchangeService);
    }
}
