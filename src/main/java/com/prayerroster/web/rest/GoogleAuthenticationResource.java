package com.prayerroster.web.rest;

import com.prayerroster.service.GoogleAuthenticationService;
import com.prayerroster.service.dto.AuthorizationUrlResponse;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The backend-owned Google sign-in flow. Both endpoints are unauthenticated by necessity - they are
 * how a caller obtains the credential every other endpoint requires. The token is returned in the
 * response body rather than a redirect URL, so it never reaches browser history or an access log,
 * and it is never logged here. See docs/phase1-architecture.md section 10.
 */
@RestController
@RequestMapping("/api/auth/google")
public class GoogleAuthenticationResource {

    private static final String ENTITY_NAME = "authentication";

    private final GoogleAuthenticationService googleAuthenticationService;

    public GoogleAuthenticationResource(GoogleAuthenticationService googleAuthenticationService) {
        this.googleAuthenticationService = googleAuthenticationService;
    }

    @GetMapping("/url")
    public AuthorizationUrlResponse getAuthorizationUrl() {
        return googleAuthenticationService.authorizationUrl();
    }

    @GetMapping("/callback")
    public GoogleTokenResponse callback(
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
        return googleAuthenticationService.completeLogin(code, state);
    }
}
