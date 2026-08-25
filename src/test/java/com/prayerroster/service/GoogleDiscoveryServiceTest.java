package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.prayerroster.web.rest.errors.GoogleAuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GoogleDiscoveryServiceTest {

    private static final String ISSUER = "https://accounts.google.com";
    private static final String DISCOVERY_URL = ISSUER + "/.well-known/openid-configuration";
    private static final String METADATA =
        "{\"authorization_endpoint\":\"https://accounts.google.com/o/oauth2/v2/auth\"," +
        "\"token_endpoint\":\"https://oauth2.googleapis.com/token\"}";

    private MockRestServiceServer server;
    private GoogleDiscoveryService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new GoogleDiscoveryService(builder, ISSUER);
    }

    @Test
    void authorizationEndpoint_isReadFromTheDiscoveryDocument() {
        server.expect(requestTo(DISCOVERY_URL)).andRespond(withSuccess(METADATA, MediaType.APPLICATION_JSON));

        assertThat(service.authorizationEndpoint()).isEqualTo("https://accounts.google.com/o/oauth2/v2/auth");
    }

    @Test
    void tokenEndpoint_isReadFromTheDiscoveryDocument() {
        server.expect(requestTo(DISCOVERY_URL)).andRespond(withSuccess(METADATA, MediaType.APPLICATION_JSON));

        assertThat(service.tokenEndpoint()).isEqualTo("https://oauth2.googleapis.com/token");
    }

    @Test
    void theDocumentIsFetchedOnlyOnce() {
        server.expect(ExpectedCount.once(), requestTo(DISCOVERY_URL)).andRespond(withSuccess(METADATA, MediaType.APPLICATION_JSON));

        service.authorizationEndpoint();
        service.tokenEndpoint();

        server.verify();
    }

    @Test
    void aFailedFetchIsReportedAsAnUpstreamFailure() {
        server.expect(requestTo(DISCOVERY_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> service.tokenEndpoint()).isInstanceOf(GoogleAuthenticationException.class);
    }

    @Test
    void aDocumentMissingTheTokenEndpointIsAnUpstreamFailure() {
        server.expect(requestTo(DISCOVERY_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.tokenEndpoint())
            .isInstanceOf(GoogleAuthenticationException.class)
            .hasMessageContaining("token_endpoint");
    }
}
