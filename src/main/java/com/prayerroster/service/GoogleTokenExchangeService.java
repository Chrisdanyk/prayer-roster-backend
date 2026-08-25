package com.prayerroster.service;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.GoogleAuthenticationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Exchanges an authorization code for a Google ID token. The client secret and the code never leave
 * the server, and neither the code nor the resulting token is ever logged or echoed back to the
 * caller - see docs/phase1-architecture.md section 10.
 */
@Service
public class GoogleTokenExchangeService {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleTokenExchangeService.class);

    private final RestClient restClient;
    private final GoogleDiscoveryService discoveryService;
    private final ApplicationProperties applicationProperties;

    public GoogleTokenExchangeService(
        RestClient.Builder restClientBuilder,
        GoogleDiscoveryService discoveryService,
        ApplicationProperties applicationProperties
    ) {
        this.restClient = restClientBuilder.build();
        this.discoveryService = discoveryService;
        this.applicationProperties = applicationProperties;
    }

    public GoogleTokenResponse exchange(String code, String codeVerifier) {
        ApplicationProperties.Google google = applicationProperties.getGoogle();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("client_id", google.getClientId());
        form.add("client_secret", google.getClientSecret());
        form.add("redirect_uri", google.getRedirectUri());

        Map<String, Object> body = post(form);
        Object idToken = body == null ? null : body.get("id_token");
        if (idToken == null) {
            throw new GoogleAuthenticationException("Google's token response contained no id_token");
        }
        Object expiresIn = body.get("expires_in");
        return new GoogleTokenResponse((String) idToken, expiresIn instanceof Number number ? number.longValue() : 0L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(MultiValueMap<String, String> form) {
        try {
            return restClient
                .post()
                .uri(discoveryService.tokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        } catch (RestClientException e) {
            // The cause is deliberately NOT attached: ExceptionTranslator builds the ProblemDetail's
            // "detail" from the cause chain, so chaining here would republish Google's raw response
            // body - which can echo the authorization code - to an unauthenticated caller. Logged
            // server-side instead, where diagnosing a failed exchange actually happens.
            LOG.warn("Google rejected the authorization code exchange: {}", e.getMessage());
            throw new GoogleAuthenticationException("Google rejected the authorization code exchange");
        }
    }
}
