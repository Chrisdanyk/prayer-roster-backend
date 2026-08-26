package com.prayerroster.web.rest;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.service.GoogleAuthenticationService;
import com.prayerroster.service.HandoffStore;
import com.prayerroster.service.dto.AuthorizationUrlResponse;
import com.prayerroster.service.dto.ExchangeHandoffRequest;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.GoogleAuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
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
    private static final String STATE_COOKIE_NAME = "prayer_roster_auth_state";
    private static final String STATE_COOKIE_PATH = "/api/auth";
    private static final Duration STATE_COOKIE_MAX_AGE = Duration.ofSeconds(300);

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

    /**
     * Alongside the JSON body, sets an {@code HttpOnly}, {@code SameSite=Lax} cookie carrying the
     * same {@code state} the caller is about to be redirected to Google with. {@link #callback}
     * compares the two, which is what binds the eventual callback to the browser that started this
     * flow rather than to whoever merely holds the {@code state} value.
     */
    @GetMapping("/google/url")
    public AuthorizationUrlResponse getAuthorizationUrl(HttpServletRequest request, HttpServletResponse response) {
        AuthorizationUrlResponse authorizationUrl = googleAuthenticationService.authorizationUrl();
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            stateCookie(authorizationUrl.state(), STATE_COOKIE_MAX_AGE, secureStateCookie(request)).toString()
        );
        return authorizationUrl;
    }

    /**
     * Two branches, both deliberate. With a frontend configured the browser is redirected to it
     * carrying an opaque handoff - a SPA cannot read a JSON body it was navigated to. Without one,
     * the JSON body is returned exactly as before, which is the manual path live verification uses.
     *
     * <p>The {@code state} cookie {@link #getAuthorizationUrl} set is compared against the {@code
     * state} query parameter; a missing or mismatched cookie is rejected the same way an unknown
     * server-side {@code state} already is - {@link BadRequestAlertException} with error key
     * {@code invalidState}. This is what closes a login-CSRF: without it, an attacker could start
     * their own flow, obtain their own {@code code}/{@code state} pair, then feed a victim's browser
     * {@code …/callback?code=<attacker>&state=<attacker>}; the victim's browser would complete the
     * exchange and store the <em>attacker's</em> identity with no way to detect it never started
     * this flow itself.
     *
     * <p><strong>The cookie check is enforced only when {@code application.frontend.base-url} is
     * configured.</strong> With no frontend configured, the caller driving this endpoint is curl,
     * not a browser - this is this project's only manual live-verification path, since a full
     * {@code @SpringBootTest} cannot boot here - so there is no cookie to bind, and enforcing one
     * would break the only way this flow can be exercised by hand.
     *
     * <p>The cookie is cleared (a second {@code Set-Cookie} with {@code Max-Age=0}) once consumed
     * here, on both the success and the rejection path. When a frontend is configured, a rejection
     * (denied consent, missing code, or invalid/mismatched state) redirects back to the SPA with
     * {@code ?error=<key>} instead of dead-ending the user on this origin's {@code problem+json}
     * body; with none configured, the 400 stays exactly as before.
     *
     * <p>{@link GoogleAuthenticationException} - thrown when Google's token endpoint itself fails or
     * answers unusably, e.g. an already-spent authorization code re-sent by refreshing this page - is
     * handled the same way: with a frontend configured it redirects to {@code
     * ?error=upstreamFailure} rather than leaving the browser on this origin's 502 {@code
     * problem+json} with no route back to the app; with none configured, the 502 stays exactly as
     * before.
     */
    @GetMapping("/google/callback")
    public ResponseEntity<GoogleTokenResponse> callback(
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String error,
        @CookieValue(name = STATE_COOKIE_NAME, required = false) String stateCookie,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        String frontendBaseUrl = applicationProperties.getFrontend().getBaseUrl();
        boolean frontendConfigured = frontendBaseUrl != null && !frontendBaseUrl.isBlank();
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie(null, Duration.ZERO, secureStateCookie(request)).toString());
        try {
            if (error != null) {
                throw new BadRequestAlertException("Google refused the sign-in request", ENTITY_NAME, "authorizationDenied");
            }
            if (code == null) {
                throw new BadRequestAlertException("The callback carried no authorization code", ENTITY_NAME, "missingCode");
            }
            if (frontendConfigured && (stateCookie == null || !stateCookie.equals(state))) {
                throw new BadRequestAlertException(
                    "Unknown, expired, or already used authorization state",
                    ENTITY_NAME,
                    "invalidState"
                );
            }
            GoogleTokenResponse token = googleAuthenticationService.completeLogin(code, state);
            if (!frontendConfigured) {
                return ResponseEntity.ok(token);
            }
            String handoff = handoffStore.issue(token);
            return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(frontendBaseUrl + "/auth/callback?handoff=" + handoff))
                .build();
        } catch (BadRequestAlertException e) {
            if (!frontendConfigured) {
                throw e;
            }
            return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(frontendBaseUrl + "/auth/callback?error=" + e.getErrorKey()))
                .build();
        } catch (GoogleAuthenticationException e) {
            if (!frontendConfigured) {
                throw e;
            }
            return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(frontendBaseUrl + "/auth/callback?error=upstreamFailure"))
                .build();
        }
    }

    private ResponseCookie stateCookie(String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(STATE_COOKIE_NAME, value == null ? "" : value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path(STATE_COOKIE_PATH)
            .maxAge(maxAge)
            .build();
    }

    /**
     * {@code server.forward-headers-strategy} is only configured for the dev profile - JHipster
     * ships no production default - so behind a TLS-terminating proxy in production {@code
     * request.isSecure()} reports {@code false} and the state cookie would ship without {@code
     * Secure}. Rather than depend on proxy headers being set correctly, this also treats the
     * deployment as TLS when the configured frontend URL is itself {@code https://}: the cookie
     * only ever exists on the SPA branch, and that branch only activates when {@code
     * frontend.base-url} is set, so an {@code https://} frontend URL already implies a TLS
     * deployment. Do not simplify this back to {@code request.isSecure()} alone - that is precisely
     * the gap this closes.
     */
    private boolean secureStateCookie(HttpServletRequest request) {
        String frontendBaseUrl = applicationProperties.getFrontend().getBaseUrl();
        return request.isSecure() || (frontendBaseUrl != null && frontendBaseUrl.startsWith("https://"));
    }

    @PostMapping("/exchange")
    public GoogleTokenResponse exchange(@Valid @RequestBody ExchangeHandoffRequest request) {
        return handoffStore.redeem(request.handoff());
    }
}
