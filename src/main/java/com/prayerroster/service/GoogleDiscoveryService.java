package com.prayerroster.service;

import com.prayerroster.web.rest.errors.GoogleAuthenticationException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads Google's authorization and token endpoints from the issuer's OIDC metadata rather than
 * hardcoding them, so the existing {@code GOOGLE_ISSUER} override stays meaningful and a mock
 * provider could be substituted later. {@code JwtDecoders.fromOidcIssuerLocation} already relies on
 * the same document for validation. It is immutable in practice, so it is fetched once and held for
 * the life of the application.
 */
@Service
public class GoogleDiscoveryService {

    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    private final RestClient restClient;
    private final String issuerUri;

    private Map<String, Object> metadata;

    public GoogleDiscoveryService(
        RestClient.Builder restClientBuilder,
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri
    ) {
        this.restClient = restClientBuilder.build();
        this.issuerUri = issuerUri;
    }

    public String authorizationEndpoint() {
        return endpoint("authorization_endpoint");
    }

    public String tokenEndpoint() {
        return endpoint("token_endpoint");
    }

    private synchronized String endpoint(String key) {
        if (metadata == null) {
            metadata = fetchMetadata();
        }
        Object value = metadata.get(key);
        if (value == null) {
            throw new GoogleAuthenticationException("Google's discovery document has no " + key);
        }
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchMetadata() {
        try {
            return restClient.get().uri(issuerUri + DISCOVERY_PATH).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new GoogleAuthenticationException("Could not read Google's discovery document", e);
        }
    }
}
