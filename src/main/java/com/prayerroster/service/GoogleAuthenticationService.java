package com.prayerroster.service;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.service.AuthorizationRequestStore.PendingAuthorization;
import com.prayerroster.service.dto.AuthorizationUrlResponse;
import com.prayerroster.service.dto.GoogleTokenResponse;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Drives the authorization-code flow: builds the URL the user signs in at, then turns the code
 * Google hands back into an ID token. The resulting token is an ordinary Google ID token, so it
 * flows through the unchanged resource-server pipeline exactly as a frontend-issued one would.
 * See docs/phase1-architecture.md section 10.
 */
@Service
public class GoogleAuthenticationService {

    private static final String SCOPE = "openid email profile";

    private final GoogleDiscoveryService discoveryService;
    private final AuthorizationRequestStore requestStore;
    private final GoogleTokenExchangeService tokenExchangeService;
    private final ApplicationProperties applicationProperties;

    public GoogleAuthenticationService(
        GoogleDiscoveryService discoveryService,
        AuthorizationRequestStore requestStore,
        GoogleTokenExchangeService tokenExchangeService,
        ApplicationProperties applicationProperties
    ) {
        this.discoveryService = discoveryService;
        this.requestStore = requestStore;
        this.tokenExchangeService = tokenExchangeService;
        this.applicationProperties = applicationProperties;
    }

    public AuthorizationUrlResponse authorizationUrl() {
        PendingAuthorization pending = requestStore.create();
        ApplicationProperties.Google google = applicationProperties.getGoogle();
        String url = UriComponentsBuilder.fromUriString(discoveryService.authorizationEndpoint())
            .queryParam("client_id", encode(google.getClientId()))
            .queryParam("redirect_uri", encode(google.getRedirectUri()))
            .queryParam("response_type", "code")
            .queryParam("scope", encode(SCOPE))
            .queryParam("state", encode(pending.state()))
            .queryParam("code_challenge", encode(pending.codeChallenge()))
            .queryParam("code_challenge_method", "S256")
            .build(true)
            .toUriString();
        return new AuthorizationUrlResponse(url, pending.state());
    }

    /**
     * Values are encoded individually rather than via {@code UriComponents.encode()}, which leaves
     * ':' and '/' intact because RFC 3986 permits them in a query component. Google's documented
     * examples pass a fully-encoded redirect_uri, and an unencoded one would break outright if the
     * configured URI ever carried its own query string.
     */
    private static String encode(String value) {
        return UriUtils.encode(value, StandardCharsets.UTF_8);
    }

    public GoogleTokenResponse completeLogin(String code, String state) {
        String codeVerifier = requestStore.consume(state);
        return tokenExchangeService.exchange(code, codeVerifier);
    }
}
