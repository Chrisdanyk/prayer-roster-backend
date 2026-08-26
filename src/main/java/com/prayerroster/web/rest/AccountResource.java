package com.prayerroster.web.rest;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.prayerroster.domain.User;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.security.SecurityUtils;
import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AccountResource {

    private static final Logger LOG = LoggerFactory.getLogger(AccountResource.class);

    private final UserRepository userRepository;

    public AccountResource(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    private static class AccountResourceException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private AccountResourceException(String message) {
            super(message);
        }
    }

    /**
     * {@code GET  /account} : get the current user.
     *
     * @param principal the current user; resolves to {@code null} if not authenticated.
     * @return the current user.
     * @throws AccountResourceException {@code 500 (Internal Server Error)} if the user couldn't be returned.
     */
    @GetMapping("/account")
    public UserVM getAccount(Principal principal) {
        LOG.debug("REST request to get the current account");
        if (principal instanceof AbstractAuthenticationToken) {
            return getUserFromAuthentication((AbstractAuthenticationToken) principal);
        } else {
            throw new AccountResourceException("User could not be found");
        }
    }

    /**
     * {@code GET  /authenticate} : check if the user is authenticated.
     *
     * @return the {@link ResponseEntity} with status {@code 204 (No Content)},
     * or with status {@code 401 (Unauthorized)} if not authenticated.
     */
    @GetMapping("/authenticate")
    public ResponseEntity<Void> isAuthenticated(Principal principal) {
        LOG.debug("REST request to check if the current user is authenticated");
        return ResponseEntity.status(principal == null ? HttpStatus.UNAUTHORIZED : HttpStatus.NO_CONTENT).build();
    }

    /**
     * The caller's own account. Identity comes from the ID token's claims, but the two service
     * capabilities and the application role live in our own {@code User} row, not in anything
     * Google issues - so they are read here rather than left to {@code GET /api/users/{id}},
     * which is USER_VIEW-gated and therefore closed to a plain member asking about themselves.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class UserVM {

        private String login;
        private Set<String> authorities;
        private Map<String, Object> details;
        private String roleName;
        private boolean canModerate;
        private boolean canPreach;

        UserVM(String login, Set<String> authorities, Map<String, Object> details, String roleName, boolean canModerate, boolean canPreach) {
            this.login = login;
            this.authorities = authorities;
            this.details = details;
            this.roleName = roleName;
            this.canModerate = canModerate;
            this.canPreach = canPreach;
        }

        public boolean isActivated() {
            return true;
        }

        /**
         * The application role - what the caller may do in the software. Deliberately distinct
         * from the service capabilities below, which say what they may do during a prayer service.
         */
        public String getRoleName() {
            return roleName;
        }

        public boolean isCanModerate() {
            return canModerate;
        }

        public boolean isCanPreach() {
            return canPreach;
        }

        public Set<String> getAuthorities() {
            return authorities;
        }

        public String getLogin() {
            return login;
        }

        @JsonAnyGetter
        public Map<String, Object> getDetails() {
            return details;
        }
    }

    private UserVM getUserFromAuthentication(AbstractAuthenticationToken authToken) {
        if (!(authToken instanceof JwtAuthenticationToken jwtAuthToken)) {
            throw new IllegalArgumentException("AuthenticationToken is not a JWT - this backend is a stateless Resource Server only!");
        }
        Map<String, Object> attributes = jwtAuthToken.getTokenAttributes();
        // Join-fetches the role in the same query: this endpoint is called on every page load of
        // the SPA, so a lazy role would be one extra round trip each time (and, with
        // open-in-view disabled, a LazyInitializationException rather than a second query).
        Optional<User> user = userRepository.findByIdWithRole(authToken.getName());

        return new UserVM(
            authToken.getName(),
            authToken.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet()),
            SecurityUtils.extractDetailsFromTokenAttributes(attributes),
            user.map(u -> u.getRole().getName()).orElse(null),
            user.map(User::isCanModerate).orElse(false),
            user.map(User::isCanPreach).orElse(false)
        );
    }
}
