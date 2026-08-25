package com.prayerroster.web.rest;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.service.GoogleAuthenticationService;
import com.prayerroster.service.HandoffStore;
import com.prayerroster.service.dto.AuthorizationUrlResponse;
import com.prayerroster.service.dto.ExchangeHandoffRequest;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The backend-owned Google sign-in flow. Both Google endpoints are unauthenticated by necessity -
 * they are how a caller obtains the credential every other endpoint requires. The token is never
 * logged here. See docs/phase1-architecture.md section 10.
 */
@RestController
@RequestMapping("/api/auth")
public class GoogleAuthenticationResource {

    private static final String ENTITY_NAME = "authentication";

    private final GoogleAuthenticationService googleAuthenticationService;
    private final HandoffStore handoffStore;
    private final ApplicationProperties applicationProperties;

    public GoogleAuthenticationResource(
        GoogleAuthenticationService googleAuthenticationService,
        HandoffStore handoffStore,
        ApplicationProperties applicationProperties
    ) {
        this.googleAuthenticationService = googleAuthenticationService;
        this.handoffStore = handoffStore;
        this.applicationProperties = applicationProperties;
    }

    @GetMapping("/google/url")
    public AuthorizationUrlResponse getAuthorizationUrl() {
        return googleAuthenticationService.authorizationUrl();
    }

    /**
     * Two branches, both deliberate. With a frontend configured the browser is redirected to it
     * carrying an opaque handoff - a SPA cannot read a JSON body it was navigated to. Without one,
     * the JSON body is returned exactly as before, which is the manual path live verification uses.
     */
    @GetMapping("/google/callback")
    public ResponseEntity<GoogleTokenResponse> callback(
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String error
    ) {
        if (error != null) {
            throw new BadRequestAlertException("Google refused the sign-in request", ENTITY_NAME, "authorizationDenied");
        }
        if (code == null) {
            throw new BadRequestAlertException("The callback carried no authorization code", ENTITY_NAME, "missingCode");
        }
        GoogleTokenResponse token = googleAuthenticationService.completeLogin(code, state);
        String frontendBaseUrl = applicationProperties.getFrontend().getBaseUrl();
        if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) {
            return ResponseEntity.ok(token);
        }
        String handoff = handoffStore.issue(token);
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(frontendBaseUrl + "/auth/callback?handoff=" + handoff))
            .build();
    }

    @PostMapping("/exchange")
    public GoogleTokenResponse exchange(@Valid @RequestBody ExchangeHandoffRequest request) {
        return handoffStore.redeem(request.handoff());
    }
}
