package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.GoogleAuthenticationException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class GoogleTokenExchangeServiceTest {

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    @Mock
    private GoogleDiscoveryService discoveryService;

    private MockRestServiceServer server;
    private GoogleTokenExchangeService service;

    @BeforeEach
    void setUp() {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getGoogle().setClientId("client-id");
        properties.getGoogle().setClientSecret("client-secret");
        properties.getGoogle().setRedirectUri("https://app.example.com/api/auth/google/callback");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new GoogleTokenExchangeService(builder, discoveryService, properties);
    }

    @Test
    void exchange_postsTheCodeAndReturnsTheIdToken() {
        when(discoveryService.tokenEndpoint()).thenReturn(TOKEN_ENDPOINT);
        server
            .expect(requestTo(TOKEN_ENDPOINT))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string(Matchers.containsString("grant_type=authorization_code")))
            .andExpect(content().string(Matchers.containsString("code_verifier=verifier-1")))
            .andExpect(content().string(Matchers.containsString("client_secret=client-secret")))
            .andRespond(withSuccess("{\"id_token\":\"eyJhbGciOi\",\"expires_in\":3599}", MediaType.APPLICATION_JSON));

        GoogleTokenResponse response = service.exchange("code-1", "verifier-1");

        assertThat(response.idToken()).isEqualTo("eyJhbGciOi");
        assertThat(response.expiresIn()).isEqualTo(3599L);
    }

    @Test
    void exchange_reportsAGoogleRejectionAsAnUpstreamFailure() {
        when(discoveryService.tokenEndpoint()).thenReturn(TOKEN_ENDPOINT);
        server
            .expect(requestTo(TOKEN_ENDPOINT))
            .andRespond(withBadRequest().body("{\"error\":\"invalid_grant\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.exchange("code-1", "verifier-1")).isInstanceOf(GoogleAuthenticationException.class);
    }

    @Test
    void exchange_neverLeaksGooglesResponseBodyToTheCaller() {
        when(discoveryService.tokenEndpoint()).thenReturn(TOKEN_ENDPOINT);
        server
            .expect(requestTo(TOKEN_ENDPOINT))
            .andRespond(withBadRequest().body("{\"error\":\"invalid_grant\",\"code\":\"secret-code-1\"}").contentType(MediaType.APPLICATION_JSON));

        // The cause must not be attached: ExceptionTranslator derives the ProblemDetail's "detail"
        // from the cause chain, so a chained RestClientException republishes Google's raw body to an
        // unauthenticated caller. Asserting on the message alone missed this - it took a live run to
        // see the leak in an actual 502.
        assertThatThrownBy(() -> service.exchange("secret-code-1", "verifier-1"))
            .isInstanceOf(GoogleAuthenticationException.class)
            .hasMessageNotContaining("secret-code-1")
            .hasNoCause();
    }

    @Test
    void exchange_rejectsAResponseWithNoIdToken() {
        when(discoveryService.tokenEndpoint()).thenReturn(TOKEN_ENDPOINT);
        server.expect(requestTo(TOKEN_ENDPOINT)).andRespond(withSuccess("{\"expires_in\":3599}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.exchange("code-1", "verifier-1"))
            .isInstanceOf(GoogleAuthenticationException.class)
            .hasMessageContaining("id_token");
    }

    @Test
    void exchange_rejectsAnEmptyResponseBody() {
        when(discoveryService.tokenEndpoint()).thenReturn(TOKEN_ENDPOINT);
        server.expect(requestTo(TOKEN_ENDPOINT)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.exchange("code-1", "verifier-1")).isInstanceOf(GoogleAuthenticationException.class);
    }

    @Test
    void exchange_defaultsExpiryWhenGoogleOmitsIt() {
        when(discoveryService.tokenEndpoint()).thenReturn(TOKEN_ENDPOINT);
        server.expect(requestTo(TOKEN_ENDPOINT)).andRespond(withSuccess("{\"id_token\":\"eyJhbGciOi\"}", MediaType.APPLICATION_JSON));

        assertThat(service.exchange("code-1", "verifier-1").expiresIn()).isZero();
    }
}
