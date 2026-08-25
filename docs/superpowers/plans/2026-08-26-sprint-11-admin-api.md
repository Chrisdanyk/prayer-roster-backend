# Sprint 11 — Admin API & SPA Auth Landing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unblock a browser client from signing in, and build the admin API surface that half the seeded permission catalogue was waiting for.

**Architecture:** The Google callback gains a second branch — when a frontend base URL is configured it redirects to the SPA with a single-use handoff code, which the SPA redeems for the ID token; otherwise it returns JSON exactly as today, preserving the only manual verification path this project has. The remaining work is resources and DTOs over a domain layer that is already complete.

**Tech Stack:** Java 17, Spring Boot 3.4.5, Spring Security OAuth2 Resource Server, Spring Data JPA, PostgreSQL, Liquibase, Caffeine, JUnit 5 + Mockito + standalone MockMvc, JaCoCo.

**Spec:** `docs/superpowers/specs/2026-08-26-admin-api-and-spa-auth-design.md`

## Global Constraints

- Run every command from `backend/`. **The build requires JDK 17** — the enforcer rejects the machine default. Prefix every Maven command: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`
- **100% line and branch coverage** on new `service` and `web/rest` classes — `./mvnw verify` fails otherwise. `config`, `domain`, `repository`, `service/dto`, `web/rest/errors` are excluded by JaCoCo.
- Verify with `./mvnw -o verify -DskipITs`. A full `@SpringBootTest` cannot boot (Timefold scans for `ConstraintProvider` and finds the test double), so tests are Mockito + standalone MockMvc only.
- **Never** a role-name check. Gate with `@PreAuthorize("hasAuthority('PERM_X')")`.
- `ApplicationProperties` binds with `ignoreUnknownFields = false` — every new `application.*` key needs a matching field or the context will not start.
- All user-facing text goes in `messages_fr.properties` and `messages_en.properties`.
- DTOs are Java records with a static `from(entity)` factory. No MapStruct.
- Layering is ArchUnit-enforced: config → web → service → security → repository → domain.
- Liquibase changelogs are hand-written and registered in `master.xml`. IDs use `sequence_generator`, `allocationSize = 50`.
- Prefer an explicit `@Query` over a derived query name — Sprint 7 shipped one that failed at real repository initialisation because Spring Data split it on a reserved keyword, and mocked repositories never surface that.
- Never log a token, an authorization code, or a handoff value.

---

### Task 1: SPA auth landing

Blocks every authenticated frontend screen. Build first.

**Files:**
- Create: `src/main/java/com/prayerroster/service/HandoffStore.java`
- Create: `src/main/java/com/prayerroster/service/dto/ExchangeHandoffRequest.java`
- Modify: `src/main/java/com/prayerroster/config/ApplicationProperties.java`
- Modify: `src/main/java/com/prayerroster/web/rest/GoogleAuthenticationResource.java`
- Modify: `src/main/java/com/prayerroster/config/SecurityConfiguration.java`
- Modify: `src/main/resources/config/application.yml`, `application-dev.yml`
- Test: `src/test/java/com/prayerroster/service/HandoffStoreTest.java`
- Test: `src/test/java/com/prayerroster/web/rest/GoogleAuthenticationResourceTest.java`

**Interfaces:**
- Consumes: `GoogleTokenResponse(String idToken, long expiresIn)`, `GoogleAuthenticationService.completeLogin(String, String)`.
- Produces: `HandoffStore.issue(GoogleTokenResponse) : String`, `HandoffStore.redeem(String) : GoogleTokenResponse`, `ApplicationProperties.getFrontend().getBaseUrl()`.

- [ ] **Step 1: Write the failing handoff store test**

Create `HandoffStoreTest.java`:

```java
package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HandoffStoreTest {

    private static final GoogleTokenResponse TOKEN = new GoogleTokenResponse("id-token", 3599);

    private HandoffStore store;

    @BeforeEach
    void setUp() {
        store = new HandoffStore();
    }

    @Test
    void issue_returnsAnOpaqueValueThatIsNotTheToken() {
        String handoff = store.issue(TOKEN);

        assertThat(handoff).isNotBlank().doesNotContain("id-token");
    }

    @Test
    void issue_producesAUniqueValueEachTime() {
        assertThat(store.issue(TOKEN)).isNotEqualTo(store.issue(TOKEN));
    }

    @Test
    void redeem_returnsTheTokenForAKnownHandoff() {
        String handoff = store.issue(TOKEN);

        assertThat(store.redeem(handoff)).isEqualTo(TOKEN);
    }

    @Test
    void redeem_rejectsAReplayedHandoff() {
        String handoff = store.issue(TOKEN);
        store.redeem(handoff);

        assertThatThrownBy(() -> store.redeem(handoff)).isInstanceOf(BadRequestAlertException.class);
    }

    @Test
    void redeem_rejectsAnUnknownHandoff() {
        assertThatThrownBy(() -> store.redeem("never-issued")).isInstanceOf(BadRequestAlertException.class);
    }

    @Test
    void redeem_rejectsAMissingHandoff() {
        assertThatThrownBy(() -> store.redeem(null)).isInstanceOf(BadRequestAlertException.class);
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; ./mvnw -o test -Dtest=HandoffStoreTest`
Expected: FAIL — `HandoffStore` does not exist.

- [ ] **Step 3: Write the handoff store**

```java
package com.prayerroster.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.stereotype.Service;

/**
 * Holds a freshly-minted ID token for the few milliseconds between the Google callback redirecting
 * the browser and the SPA redeeming it. The handoff is opaque and single-use, so the token itself
 * never travels in a URL - which is the entire point: a token in a query string reaches browser
 * history, Referer headers and access logs. See docs/phase1-architecture.md section 10.
 */
@Service
public class HandoffStore {

    private static final String ENTITY_NAME = "authentication";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Cache<String, GoogleTokenResponse> tokensByHandoff = Caffeine.newBuilder()
        .expireAfterWrite(TTL)
        .maximumSize(10_000)
        .build();

    public String issue(GoogleTokenResponse token) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String handoff = encoder.encodeToString(bytes);
        tokensByHandoff.put(handoff, token);
        return handoff;
    }

    /** Single use: removed atomically, so a replayed handoff fails. */
    public GoogleTokenResponse redeem(String handoff) {
        GoogleTokenResponse token = handoff == null ? null : tokensByHandoff.asMap().remove(handoff);
        if (token == null) {
            throw new BadRequestAlertException("Unknown, expired, or already used handoff", ENTITY_NAME, "invalidHandoff");
        }
        return token;
    }
}
```

- [ ] **Step 4: Run it to make sure it passes**

Run: `./mvnw -o test -Dtest=HandoffStoreTest`
Expected: PASS (6 tests)

- [ ] **Step 5: Add the frontend base URL property**

In `ApplicationProperties`, alongside the existing `Google` nested class:

```java
    private final Frontend frontend = new Frontend();

    public Frontend getFrontend() {
        return frontend;
    }

    /**
     * When set, the Google callback redirects the browser here with a single-use handoff instead of
     * returning JSON. Left blank, the JSON branch stays active - which is how this project performs
     * live verification, since a full @SpringBootTest cannot boot here.
     */
    public static class Frontend {

        private String baseUrl;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
```

In `application.yml`, under the `application:` document:

```yaml
  frontend:
    base-url: ${FRONTEND_BASE_URL:}
```

In `application-dev.yml`, under the existing `application:` block:

```yaml
  frontend:
    base-url: ${FRONTEND_BASE_URL:http://localhost:3000}
```

- [ ] **Step 6: Write the failing resource tests**

Add to `GoogleAuthenticationResourceTest`. The resource gains two constructor arguments, so update the existing `@BeforeEach` to build it with `new GoogleAuthenticationResource(googleAuthenticationService, handoffStore, applicationProperties)` where `applicationProperties` is a real `new ApplicationProperties()` and `handoffStore` is a `@Mock HandoffStore`.

```java
@Test
void callback_returnsJsonWhenNoFrontendIsConfigured() throws Exception {
    when(googleAuthenticationService.completeLogin("code-1", "state-1")).thenReturn(new GoogleTokenResponse("id-token", 3599));

    mockMvc
        .perform(get("/api/auth/google/callback").param("code", "code-1").param("state", "state-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.idToken").value("id-token"));

    verifyNoInteractions(handoffStore);
}

@Test
void callback_redirectsToTheFrontendWithAHandoffWhenConfigured() throws Exception {
    applicationProperties.getFrontend().setBaseUrl("https://app.example.com");
    GoogleTokenResponse token = new GoogleTokenResponse("id-token", 3599);
    when(googleAuthenticationService.completeLogin("code-1", "state-1")).thenReturn(token);
    when(handoffStore.issue(token)).thenReturn("handoff-1");

    mockMvc
        .perform(get("/api/auth/google/callback").param("code", "code-1").param("state", "state-1"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://app.example.com/auth/callback?handoff=handoff-1"));
}

@Test
void callback_neverPutsTheTokenInTheRedirectUrl() throws Exception {
    applicationProperties.getFrontend().setBaseUrl("https://app.example.com");
    GoogleTokenResponse token = new GoogleTokenResponse("super-secret-token", 3599);
    when(googleAuthenticationService.completeLogin("code-1", "state-1")).thenReturn(token);
    when(handoffStore.issue(token)).thenReturn("handoff-1");

    String location = mockMvc
        .perform(get("/api/auth/google/callback").param("code", "code-1").param("state", "state-1"))
        .andReturn()
        .getResponse()
        .getHeader("Location");

    assertThat(location).doesNotContain("super-secret-token");
}

@Test
void exchange_redeemsAHandoffForTheToken() throws Exception {
    when(handoffStore.redeem("handoff-1")).thenReturn(new GoogleTokenResponse("id-token", 3599));

    mockMvc
        .perform(
            post("/api/auth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"handoff\":\"handoff-1\"}")
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.idToken").value("id-token"));
}

@Test
void exchange_returns400OnAnInvalidHandoff() throws Exception {
    when(handoffStore.redeem("bad")).thenThrow(new BadRequestAlertException("invalid", "authentication", "invalidHandoff"));

    mockMvc
        .perform(post("/api/auth/exchange").contentType(MediaType.APPLICATION_JSON).content("{\"handoff\":\"bad\"}"))
        .andExpect(status().isBadRequest());
}
```

Add the imports `static org.assertj.core.api.Assertions.assertThat`, `org.springframework.http.MediaType`, `com.prayerroster.config.ApplicationProperties`, `com.prayerroster.service.HandoffStore`, and `static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post`.

- [ ] **Step 7: Run them to make sure they fail**

Run: `./mvnw -o test -Dtest=GoogleAuthenticationResourceTest`
Expected: FAIL — compilation error, the constructor takes one argument.

- [ ] **Step 8: Create the request DTO**

```java
package com.prayerroster.service.dto;

import jakarta.validation.constraints.NotBlank;

public record ExchangeHandoffRequest(@NotBlank String handoff) {}
```

- [ ] **Step 9: Rework the resource**

Change the class mapping to `/api/auth` and adjust the two existing paths, so the exchange endpoint is a sibling rather than living under `/google`:

```java
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
```

Add imports: `java.net.URI`, `org.springframework.http.HttpStatus`, `org.springframework.http.ResponseEntity`, `jakarta.validation.Valid`, `org.springframework.web.bind.annotation.PostMapping`, `org.springframework.web.bind.annotation.RequestBody`, plus `ApplicationProperties`, `HandoffStore`, `ExchangeHandoffRequest`.

- [ ] **Step 10: Open the exchange endpoint**

In `SecurityConfiguration.filterChain`, beneath the two existing google matchers:

```java
                    .requestMatchers(mvc.pattern("/api/auth/exchange")).permitAll()
```

- [ ] **Step 11: Add the error message**

Append to `messages_fr.properties`:

```properties
error.invalidHandoff=Session d'authentification expirée. Veuillez vous reconnecter.
```

Append to `messages_en.properties`:

```properties
error.invalidHandoff=Authentication session expired. Please sign in again.
```

- [ ] **Step 12: Run the whole suite and the gate**

Run: `./mvnw -o verify -DskipITs`
Expected: PASS, "All coverage checks have been met."

- [ ] **Step 13: Commit**

```bash
git add src/main src/test
git commit -m "Redirect the Google callback to the SPA with a single-use handoff

A browser navigated to the callback receives a JSON body the SPA can never
read. When a frontend base URL is configured the callback now redirects
there with an opaque, single-use handoff which the SPA redeems; with none
configured the JSON branch stays, since that is the manual path live
verification depends on. No token ever appears in a URL."
```

---

### Task 2: Roles and permissions API

**Files:**
- Create: `src/main/java/com/prayerroster/service/dto/PermissionDTO.java`, `RoleDTO.java`, `CreateRoleRequest.java`, `UpdateRoleRequest.java`
- Create: `src/main/java/com/prayerroster/service/RoleService.java`
- Create: `src/main/java/com/prayerroster/web/rest/RoleResource.java`, `PermissionResource.java`
- Modify: `src/main/java/com/prayerroster/security/DynamicAuthoritiesService.java`
- Modify: `src/main/java/com/prayerroster/repository/UserRepository.java`, `RoleRepository.java`
- Test: `src/test/java/com/prayerroster/service/RoleServiceTest.java`
- Test: `src/test/java/com/prayerroster/web/rest/RoleResourceTest.java`, `PermissionResourceTest.java`
- Test: `src/test/java/com/prayerroster/security/DynamicAuthoritiesServiceTest.java`

**Interfaces:**
- Produces: `RoleDTO(Long id, String name, String description, List<String> permissionCodes, long userCount)`, `PermissionDTO(Long id, String code, String description)`, `RoleService.findAll/create/update/delete`, `DynamicAuthoritiesService.evictAll()`.

- [ ] **Step 1: Add the cache-wide eviction with its test**

Add to `DynamicAuthoritiesServiceTest`:

```java
@Test
void evictAll_forcesProvisioningToBeCalledAgainForEveryUser() {
    when(provisioningService.provisionOrRefresh(IDENTITY)).thenReturn(userWith(RoleNames.USER));

    service.resolveAuthorities(IDENTITY);
    service.evictAll();
    service.resolveAuthorities(IDENTITY);

    verify(provisioningService, times(2)).provisionOrRefresh(IDENTITY);
}
```

Then in `DynamicAuthoritiesService`:

```java
    /**
     * Editing a role changes the authorities of every user holding it, and {@link #evict(String)} is
     * per-user - without this a permission change appears to do nothing for up to the cache TTL.
     */
    public void evictAll() {
        authoritiesCache.invalidateAll();
    }
```

Run: `./mvnw -o test -Dtest=DynamicAuthoritiesServiceTest` — expect PASS.

- [ ] **Step 2: Add the repository queries**

In `UserRepository`:

```java
    @Query("select count(u) from User u where u.role.id = :roleId")
    long countByRoleId(@Param("roleId") Long roleId);
```

In `RoleRepository`:

```java
    @Query("select distinct r from Role r left join fetch r.permissions order by r.name")
    List<Role> findAllWithPermissions();
```

Add the imports `java.util.List`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param` where missing.

- [ ] **Step 3: Create the DTOs**

`PermissionDTO.java`:

```java
package com.prayerroster.service.dto;

import com.prayerroster.domain.Permission;

public record PermissionDTO(Long id, String code, String description) {
    public static PermissionDTO from(Permission permission) {
        return new PermissionDTO(permission.getId(), permission.getCode(), permission.getDescription());
    }
}
```

`RoleDTO.java`:

```java
package com.prayerroster.service.dto;

import com.prayerroster.domain.Permission;
import com.prayerroster.domain.Role;
import java.util.List;

public record RoleDTO(Long id, String name, String description, List<String> permissionCodes, long userCount) {
    public static RoleDTO from(Role role, long userCount) {
        return new RoleDTO(
            role.getId(),
            role.getName(),
            role.getDescription(),
            role.getPermissions().stream().map(Permission::getCode).sorted().toList(),
            userCount
        );
    }
}
```

`CreateRoleRequest.java`:

```java
package com.prayerroster.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateRoleRequest(
    @NotBlank @Size(max = 50) String name,
    @Size(max = 200) String description,
    List<String> permissionCodes
) {}
```

`UpdateRoleRequest.java`:

```java
package com.prayerroster.service.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateRoleRequest(@Size(max = 200) String description, List<String> permissionCodes) {}
```

- [ ] **Step 4: Write the failing service tests**

Create `RoleServiceTest.java`:

```java
package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.Permission;
import com.prayerroster.domain.Role;
import com.prayerroster.repository.PermissionRepository;
import com.prayerroster.repository.RoleRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.security.DynamicAuthoritiesService;
import com.prayerroster.security.RoleNames;
import com.prayerroster.service.dto.CreateRoleRequest;
import com.prayerroster.service.dto.RoleDTO;
import com.prayerroster.service.dto.UpdateRoleRequest;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DynamicAuthoritiesService authoritiesService;

    private RoleService service;

    @BeforeEach
    void setUp() {
        service = new RoleService(roleRepository, permissionRepository, userRepository, authoritiesService);
    }

    @Test
    void findAll_returnsRolesWithTheirPermissionCodesAndUserCount() {
        when(roleRepository.findAllWithPermissions()).thenReturn(List.of(role(1L, RoleNames.ADMIN, "USER_VIEW")));
        when(userRepository.countByRoleId(1L)).thenReturn(3L);

        List<RoleDTO> result = service.findAll();

        assertThat(result).singleElement().satisfies(dto -> {
            assertThat(dto.name()).isEqualTo(RoleNames.ADMIN);
            assertThat(dto.permissionCodes()).containsExactly("USER_VIEW");
            assertThat(dto.userCount()).isEqualTo(3L);
        });
    }

    @Test
    void create_rejectsADuplicateName() {
        when(roleRepository.findByName("COORDINATOR")).thenReturn(Optional.of(role(9L, "COORDINATOR")));

        assertThatThrownBy(() -> service.create(new CreateRoleRequest("COORDINATOR", null, List.of())))
            .isInstanceOf(BadRequestAlertException.class);

        verify(roleRepository, never()).save(any());
    }

    @Test
    void create_rejectsAnUnknownPermissionCode() {
        when(roleRepository.findByName("COORDINATOR")).thenReturn(Optional.empty());
        when(permissionRepository.findByCode("NOT_A_CODE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateRoleRequest("COORDINATOR", null, List.of("NOT_A_CODE"))))
            .isInstanceOf(BadRequestAlertException.class)
            .hasMessageContaining("NOT_A_CODE");
    }

    @Test
    void create_evictsTheAuthoritiesCache() {
        when(roleRepository.findByName("COORDINATOR")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(new CreateRoleRequest("COORDINATOR", "Coordination", List.of()));

        verify(authoritiesService).evictAll();
    }

    @Test
    void update_replacesThePermissionSetAndEvicts() {
        Role existing = role(2L, "COORDINATOR", "USER_VIEW");
        Permission granted = permission("ROSTER_VIEW");
        when(roleRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(permissionRepository.findByCode("ROSTER_VIEW")).thenReturn(Optional.of(granted));
        when(roleRepository.save(existing)).thenReturn(existing);
        when(userRepository.countByRoleId(2L)).thenReturn(0L);

        RoleDTO result = service.update(2L, new UpdateRoleRequest("Coordination", List.of("ROSTER_VIEW")));

        assertThat(result.permissionCodes()).containsExactly("ROSTER_VIEW");
        verify(authoritiesService).evictAll();
    }

    @Test
    void update_allowsAddingPermissionsToSuperAdmin() {
        Role superAdmin = role(1L, RoleNames.SUPER_ADMIN, "USER_VIEW");
        when(roleRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
        when(permissionRepository.findByCode("USER_VIEW")).thenReturn(Optional.of(permission("USER_VIEW")));
        when(permissionRepository.findByCode("ROSTER_VIEW")).thenReturn(Optional.of(permission("ROSTER_VIEW")));
        when(roleRepository.save(superAdmin)).thenReturn(superAdmin);
        when(userRepository.countByRoleId(1L)).thenReturn(1L);

        RoleDTO result = service.update(1L, new UpdateRoleRequest(null, List.of("USER_VIEW", "ROSTER_VIEW")));

        assertThat(result.permissionCodes()).containsExactly("ROSTER_VIEW", "USER_VIEW");
    }

    @Test
    void update_refusesToStripPermissionsFromSuperAdmin() {
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role(1L, RoleNames.SUPER_ADMIN, "USER_VIEW")));

        // Swapping one permission for another must fail too, not just shrinking the set.
        assertThatThrownBy(() -> service.update(1L, new UpdateRoleRequest(null, List.of())))
            .isInstanceOf(BadRequestAlertException.class)
            .hasMessageContaining("SUPER_ADMIN");

        verify(roleRepository, never()).save(any());
    }

    @Test
    void update_rejectsAnUnknownRole() {
        when(roleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, new UpdateRoleRequest(null, List.of())))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void delete_refusesABaselineRole() {
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role(1L, RoleNames.USER)));

        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(BadRequestAlertException.class);

        verify(roleRepository, never()).delete(any());
    }

    @Test
    void delete_refusesARoleThatUsersStillHold() {
        when(roleRepository.findById(5L)).thenReturn(Optional.of(role(5L, "COORDINATOR")));
        when(userRepository.countByRoleId(5L)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(5L))
            .isInstanceOf(BadRequestAlertException.class)
            .hasMessageContaining("2");

        verify(roleRepository, never()).delete(any());
    }

    @Test
    void delete_removesAnUnusedCustomRoleAndEvicts() {
        Role custom = role(5L, "COORDINATOR");
        when(roleRepository.findById(5L)).thenReturn(Optional.of(custom));
        when(userRepository.countByRoleId(5L)).thenReturn(0L);

        service.delete(5L);

        verify(roleRepository).delete(custom);
        verify(authoritiesService).evictAll();
    }

    private Role role(Long id, String name, String... permissionCodes) {
        Role role = new Role();
        role.setId(id);
        role.setName(name);
        role.setPermissions(new java.util.HashSet<>(java.util.Arrays.stream(permissionCodes).map(this::permission).toList()));
        return role;
    }

    private Permission permission(String code) {
        Permission permission = new Permission();
        permission.setCode(code);
        return permission;
    }
}
```

- [ ] **Step 5: Run them to make sure they fail**

Run: `./mvnw -o test -Dtest=RoleServiceTest`
Expected: FAIL — `RoleService` does not exist.

- [ ] **Step 6: Write the service**

```java
package com.prayerroster.service;

import com.prayerroster.domain.Permission;
import com.prayerroster.domain.Role;
import com.prayerroster.repository.PermissionRepository;
import com.prayerroster.repository.RoleRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.security.DynamicAuthoritiesService;
import com.prayerroster.security.RoleNames;
import com.prayerroster.service.dto.CreateRoleRequest;
import com.prayerroster.service.dto.RoleDTO;
import com.prayerroster.service.dto.UpdateRoleRequest;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of the business role graph. Every write evicts the whole authorities cache, because
 * a role's permission change affects every user holding it and {@code evict(userId)} is per-user -
 * without this the change would appear to do nothing until the TTL expired.
 * <p>
 * The guards here each prevent a way to lock everyone out permanently. See
 * docs/phase1-architecture.md section 9.
 */
@Service
@Transactional
public class RoleService {

    private static final String ENTITY_NAME = "role";
    private static final Set<String> BASELINE_ROLES = Set.of(RoleNames.SUPER_ADMIN, RoleNames.ADMIN, RoleNames.USER);

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final DynamicAuthoritiesService authoritiesService;

    public RoleService(
        RoleRepository roleRepository,
        PermissionRepository permissionRepository,
        UserRepository userRepository,
        DynamicAuthoritiesService authoritiesService
    ) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
        this.authoritiesService = authoritiesService;
    }

    @Transactional(readOnly = true)
    public List<RoleDTO> findAll() {
        return roleRepository
            .findAllWithPermissions()
            .stream()
            .map(role -> RoleDTO.from(role, userRepository.countByRoleId(role.getId())))
            .toList();
    }

    public RoleDTO create(CreateRoleRequest request) {
        if (roleRepository.findByName(request.name()).isPresent()) {
            throw new BadRequestAlertException("A role named " + request.name() + " already exists", ENTITY_NAME, "duplicateName");
        }
        Role role = new Role();
        role.setName(request.name());
        role.setDescription(request.description());
        role.setPermissions(resolvePermissions(request.permissionCodes()));
        Role saved = roleRepository.save(role);
        authoritiesService.evictAll();
        return RoleDTO.from(saved, 0L);
    }

    public RoleDTO update(Long id, UpdateRoleRequest request) {
        Role role = roleRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Role not found: " + id));
        Set<Permission> permissions = resolvePermissions(request.permissionCodes());
        if (RoleNames.SUPER_ADMIN.equals(role.getName()) && !permissions.containsAll(role.getPermissions())) {
            throw new BadRequestAlertException(
                "SUPER_ADMIN cannot have permissions removed - it is the recovery path",
                ENTITY_NAME,
                "superAdminImmutable"
            );
        }
        role.setDescription(request.description());
        role.setPermissions(permissions);
        Role saved = roleRepository.save(role);
        authoritiesService.evictAll();
        return RoleDTO.from(saved, userRepository.countByRoleId(id));
    }

    public void delete(Long id) {
        Role role = roleRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Role not found: " + id));
        if (BASELINE_ROLES.contains(role.getName())) {
            throw new BadRequestAlertException("Baseline roles cannot be deleted", ENTITY_NAME, "baselineRole");
        }
        long holders = userRepository.countByRoleId(id);
        if (holders > 0) {
            throw new BadRequestAlertException(holders + " user(s) still hold this role", ENTITY_NAME, "roleInUse");
        }
        roleRepository.delete(role);
        authoritiesService.evictAll();
    }

    private Set<Permission> resolvePermissions(List<String> codes) {
        Set<Permission> permissions = new HashSet<>();
        if (codes == null) {
            return permissions;
        }
        for (String code : codes) {
            permissions.add(
                permissionRepository
                    .findByCode(code)
                    .orElseThrow(() -> new BadRequestAlertException("Unknown permission code: " + code, ENTITY_NAME, "unknownPermission"))
            );
        }
        return permissions;
    }
}
```

- [ ] **Step 7: Run them to make sure they pass**

Run: `./mvnw -o test -Dtest=RoleServiceTest`
Expected: PASS

- [ ] **Step 8: Write the failing resource tests**

Create `RoleResourceTest.java` and `PermissionResourceTest.java` following the standalone-MockMvc pattern used by `AllowedEmailResourceTest`: build the resource with `MockMvcBuilders.standaloneSetup(new RoleResource(roleService))`, `.setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))`, `.setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))`.

`RoleResourceTest` covers: `GET /api/roles` returns the list; `POST /api/roles` returns 201; `POST` with a blank name returns 400 and never touches the service; `PUT /api/roles/{id}` returns 200; `DELETE /api/roles/{id}` returns 204 and calls `roleService.delete(5L)`; a `BadRequestAlertException` from the service surfaces as 400.

`PermissionResourceTest` covers: `GET /api/permissions` returns the catalogue.

```java
@Test
void getRoles_returnsTheList() throws Exception {
    when(roleService.findAll()).thenReturn(List.of(new RoleDTO(1L, "ADMIN", "Gestion", List.of("USER_VIEW"), 3L)));

    mockMvc
        .perform(get("/api/roles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("ADMIN"))
        .andExpect(jsonPath("$[0].userCount").value(3));
}

@Test
void createRole_returns400OnABlankName() throws Exception {
    mockMvc
        .perform(
            post("/api/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRoleRequest("", null, List.of())))
        )
        .andExpect(status().isBadRequest());

    verifyNoInteractions(roleService);
}

@Test
void deleteRole_returns204() throws Exception {
    mockMvc.perform(delete("/api/roles/{id}", 5L)).andExpect(status().isNoContent());

    verify(roleService).delete(5L);
}
```

- [ ] **Step 9: Run them to make sure they fail**

Run: `./mvnw -o test -Dtest='RoleResourceTest,PermissionResourceTest'`
Expected: FAIL — the resources do not exist.

- [ ] **Step 10: Write the resources**

```java
package com.prayerroster.web.rest;

import com.prayerroster.service.RoleService;
import com.prayerroster.service.dto.CreateRoleRequest;
import com.prayerroster.service.dto.RoleDTO;
import com.prayerroster.service.dto.UpdateRoleRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Administration of the business role graph - see docs/phase1-architecture.md section 9. */
@RestController
@RequestMapping("/api/roles")
public class RoleResource {

    private final RoleService roleService;

    public RoleResource(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_ROLE_VIEW')")
    public List<RoleDTO> getRoles() {
        return roleService.findAll();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_ROLE_CREATE')")
    public ResponseEntity<RoleDTO> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return ResponseEntity.status(201).body(roleService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_ROLE_UPDATE')")
    public RoleDTO updateRole(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        return roleService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_ROLE_DELETE')")
    public ResponseEntity<Void> deleteRole(@PathVariable Long id) {
        roleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

```java
package com.prayerroster.web.rest;

import com.prayerroster.repository.PermissionRepository;
import com.prayerroster.service.dto.PermissionDTO;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only. The catalogue is defined in {@code config/permissions.json} and re-seeded at every
 * boot, so write endpoints would fight the seeder - see docs/phase1-architecture.md section 9.
 */
@RestController
@RequestMapping("/api/permissions")
public class PermissionResource {

    private final PermissionRepository permissionRepository;

    public PermissionResource(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_PERMISSION_VIEW')")
    public List<PermissionDTO> getPermissions() {
        return permissionRepository.findAll().stream().map(PermissionDTO::from).sorted((a, b) -> a.code().compareTo(b.code())).toList();
    }
}
```

- [ ] **Step 11: Add the error messages**

Append to `messages_fr.properties`:

```properties
error.duplicateName=Un rôle portant ce nom existe déjà.
error.unknownPermission=Permission inconnue.
error.superAdminImmutable=Les permissions du rôle SUPER_ADMIN ne peuvent pas être retirées.
error.baselineRole=Les rôles de base ne peuvent pas être supprimés.
error.roleInUse=Ce rôle est encore attribué à des utilisateurs.
```

Append the English equivalents to `messages_en.properties`.

- [ ] **Step 12: Run the whole suite and the gate**

Run: `./mvnw -o verify -DskipITs`
Expected: PASS, coverage gate green.

- [ ] **Step 13: Commit**

```bash
git add src/main src/test
git commit -m "Add the roles and permissions administration API

Eight of the thirteen unused seeded permissions now have endpoints. Every
role write evicts the whole authorities cache: a permission change affects
every holder, and evict(userId) is per-user, so without it the change would
silently wait out the 60s TTL.

Guards prevent the ways this could lock everyone out - baseline roles are
undeletable, a role in use cannot be removed, and SUPER_ADMIN cannot have
permissions stripped since it is the recovery path."
```

---

### Task 3: Roster generation history

**Files:**
- Create: `src/main/java/com/prayerroster/service/dto/RosterGenerationDTO.java`
- Modify: `src/main/java/com/prayerroster/repository/RosterGenerationRepository.java`
- Modify: `src/main/java/com/prayerroster/service/RosterService.java`
- Modify: `src/main/java/com/prayerroster/web/rest/RosterResource.java`
- Test: `src/test/java/com/prayerroster/service/RosterServiceTest.java`
- Test: `src/test/java/com/prayerroster/web/rest/RosterResourceTest.java`

**Interfaces:**
- Produces: `RosterService.findGenerations(Long rosterId) : List<RosterGenerationDTO>`.

- [ ] **Step 1: Create the DTO**

```java
package com.prayerroster.service.dto;

import com.prayerroster.domain.RosterGeneration;
import com.prayerroster.domain.RosterGenerationStatus;
import com.prayerroster.domain.RosterGenerationTrigger;
import java.time.Instant;
import java.time.LocalDate;

/** One immutable audit row per solve attempt - see docs/phase1-architecture.md section 2. */
public record RosterGenerationDTO(
    Long id,
    RosterGenerationTrigger trigger,
    RosterGenerationStatus status,
    LocalDate planningFrom,
    LocalDate planningTo,
    Integer hardScore,
    Integer softScore,
    Boolean feasible,
    Long solverDurationMs,
    String rescheduleReason,
    String errorMessage,
    Instant createdDate,
    String createdBy
) {
    public static RosterGenerationDTO from(RosterGeneration generation) {
        return new RosterGenerationDTO(
            generation.getId(),
            generation.getTrigger(),
            generation.getStatus(),
            generation.getPlanningFrom(),
            generation.getPlanningTo(),
            generation.getHardScore(),
            generation.getSoftScore(),
            generation.getFeasible(),
            generation.getSolverDurationMs(),
            generation.getRescheduleReason(),
            generation.getErrorMessage(),
            generation.getCreatedDate(),
            generation.getCreatedBy()
        );
    }
}
```

- [ ] **Step 2: Add the repository query**

`RosterGenerationRepository` is currently an empty interface:

```java
package com.prayerroster.repository;

import com.prayerroster.domain.RosterGeneration;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RosterGenerationRepository extends JpaRepository<RosterGeneration, Long> {
    /** Explicit rather than derived - see the Sprint 7 note in CLAUDE.md. */
    @Query("select g from RosterGeneration g where g.roster.id = :rosterId order by g.createdDate desc")
    List<RosterGeneration> findByRosterIdMostRecentFirst(@Param("rosterId") Long rosterId);
}
```

- [ ] **Step 3: Write the failing service test**

Add to `RosterServiceTest` (read the file first and reuse its existing mocks; it will need a new `@Mock RosterGenerationRepository generationRepository` passed to the constructor):

```java
@Test
void findGenerations_returnsTheAuditTrailMostRecentFirst() {
    when(rosterRepository.existsById(1L)).thenReturn(true);
    RosterGeneration generation = new RosterGeneration();
    generation.setId(7L);
    generation.setStatus(RosterGenerationStatus.COMPLETED);
    generation.setHardScore(0);
    generation.setSoftScore(-12);
    generation.setSolverDurationMs(1800L);
    when(generationRepository.findByRosterIdMostRecentFirst(1L)).thenReturn(List.of(generation));

    List<RosterGenerationDTO> result = service.findGenerations(1L);

    assertThat(result).singleElement().satisfies(dto -> {
        assertThat(dto.hardScore()).isZero();
        assertThat(dto.softScore()).isEqualTo(-12);
        assertThat(dto.solverDurationMs()).isEqualTo(1800L);
    });
}

@Test
void findGenerations_rejectsAnUnknownRoster() {
    when(rosterRepository.existsById(99L)).thenReturn(false);

    assertThatThrownBy(() -> service.findGenerations(99L)).isInstanceOf(EntityNotFoundException.class);
}
```

- [ ] **Step 4: Run it to make sure it fails**

Run: `./mvnw -o test -Dtest=RosterServiceTest`
Expected: FAIL — `findGenerations` does not exist.

- [ ] **Step 5: Implement the service method**

Add the `RosterGenerationRepository` constructor parameter and field to `RosterService`, then:

```java
    @Transactional(readOnly = true)
    public List<RosterGenerationDTO> findGenerations(Long rosterId) {
        if (!rosterRepository.existsById(rosterId)) {
            throw new EntityNotFoundException("Roster not found: " + rosterId);
        }
        return generationRepository.findByRosterIdMostRecentFirst(rosterId).stream().map(RosterGenerationDTO::from).toList();
    }
```

- [ ] **Step 6: Run it to make sure it passes**

Run: `./mvnw -o test -Dtest=RosterServiceTest`
Expected: PASS

- [ ] **Step 7: Add the endpoint with its test**

In `RosterResource`:

```java
    @GetMapping("/{id}/generations")
    @PreAuthorize("hasAuthority('PERM_ROSTER_VIEW')")
    public List<RosterGenerationDTO> getGenerations(@PathVariable Long id) {
        return rosterService.findGenerations(id);
    }
```

Add to `RosterResourceTest`:

```java
@Test
void getGenerations_returnsTheAuditTrail() throws Exception {
    when(rosterService.findGenerations(1L)).thenReturn(
        List.of(new RosterGenerationDTO(7L, RosterGenerationTrigger.MANUAL, RosterGenerationStatus.COMPLETED,
            LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), 0, -12, true, 1800L, null, null, null, "admin"))
    );

    mockMvc
        .perform(get("/api/rosters/{id}/generations", 1L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].hardScore").value(0))
        .andExpect(jsonPath("$[0].solverDurationMs").value(1800));
}
```

- [ ] **Step 8: Run the whole suite and the gate**

Run: `./mvnw -o verify -DskipITs`
Expected: PASS, coverage gate green.

- [ ] **Step 9: Commit**

```bash
git add src/main src/test
git commit -m "Expose the roster generation audit trail

RosterGeneration has stored every solver metric since Sprint 4 and was
never readable. Architecture section 14 promised this endpoint."
```

---

### Task 4: Admin availability

**Files:**
- Create: `src/main/java/com/prayerroster/web/rest/AdminUserAvailabilityResource.java`
- Test: `src/test/java/com/prayerroster/web/rest/AdminUserAvailabilityResourceTest.java`

**Interfaces:**
- Consumes: `UserAvailabilityService.findOwn(String)`, `create(String, AvailabilityRequest)`, `cancel(String, Long)` — these already take a user id, so no service change is needed and the rescheduling event still fires from `create`.

- [ ] **Step 1: Write the failing resource test**

Create `AdminUserAvailabilityResourceTest.java` using the standalone-MockMvc pattern, covering: `GET /api/users/{userId}/availability` returns the member's list; `POST` returns 201 and delegates with the **path** user id, not the caller's; `DELETE` returns 204.

```java
@Test
void createForUser_recordsAgainstThePathUserNotTheCaller() throws Exception {
    AvailabilityRequest request = new AvailabilityRequest(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-18"), "Voyage");
    when(userAvailabilityService.create("sub-9", request)).thenReturn(
        new UserAvailabilityDTO(1L, request.startDate(), request.endDate(), "Voyage", UserAvailabilityStatus.ACTIVE)
    );

    mockMvc
        .perform(
            post("/api/users/{userId}/availability", "sub-9")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isCreated());

    verify(userAvailabilityService).create("sub-9", request);
}
```

The `ObjectMapper` needs `registerModule(new JavaTimeModule())` and `disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)` for `LocalDate` — check how `MeAvailabilityResourceTest` builds its mapper and copy that exactly.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./mvnw -o test -Dtest=AdminUserAvailabilityResourceTest`
Expected: FAIL — the resource does not exist.

- [ ] **Step 3: Write the resource**

```java
package com.prayerroster.web.rest;

import com.prayerroster.service.UserAvailabilityService;
import com.prayerroster.service.dto.AvailabilityRequest;
import com.prayerroster.service.dto.UserAvailabilityDTO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Lets an administrator record an absence on a member's behalf - somebody phones in rather than
 * using the app. Delegates to the same service the self-service path uses, so the
 * {@code UserAvailabilityChangedEvent} still fires and rescheduling still triggers; recording an
 * absence without that event would leave the roster holding an assignment that cannot be served.
 */
@RestController
@RequestMapping("/api/users/{userId}/availability")
public class AdminUserAvailabilityResource {

    private final UserAvailabilityService userAvailabilityService;

    public AdminUserAvailabilityResource(UserAvailabilityService userAvailabilityService) {
        this.userAvailabilityService = userAvailabilityService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_AVAILABILITY_VIEW')")
    public List<UserAvailabilityDTO> getForUser(@PathVariable String userId) {
        return userAvailabilityService.findOwn(userId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_AVAILABILITY_MANAGE')")
    public ResponseEntity<UserAvailabilityDTO> createForUser(@PathVariable String userId, @Valid @RequestBody AvailabilityRequest request) {
        return ResponseEntity.status(201).body(userAvailabilityService.create(userId, request));
    }

    @DeleteMapping("/{availabilityId}")
    @PreAuthorize("hasAuthority('PERM_AVAILABILITY_MANAGE')")
    public ResponseEntity<Void> cancelForUser(@PathVariable String userId, @PathVariable Long availabilityId) {
        userAvailabilityService.cancel(userId, availabilityId);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Run it to make sure it passes**

Run: `./mvnw -o test -Dtest=AdminUserAvailabilityResourceTest`
Expected: PASS

- [ ] **Step 5: Run the whole suite and the gate**

Run: `./mvnw -o verify -DskipITs`
Expected: PASS, coverage gate green.

- [ ] **Step 6: Commit**

```bash
git add src/main src/test
git commit -m "Let administrators manage a member's availability

Delegates to the same service as the self-service path, so the rescheduling
event still fires - recording an absence without it would leave the roster
holding an assignment nobody can serve."
```

---

### Task 5: Availability conflict preview

**Files:**
- Create: `src/main/java/com/prayerroster/service/dto/ConflictingAssignmentDTO.java`
- Modify: `src/main/java/com/prayerroster/service/PrayerAssignmentService.java`
- Modify: `src/main/java/com/prayerroster/web/rest/MeAvailabilityResource.java`
- Test: `src/test/java/com/prayerroster/service/PrayerAssignmentServiceTest.java`
- Test: `src/test/java/com/prayerroster/web/rest/MeAvailabilityResourceTest.java`

**Interfaces:**
- Consumes: `PrayerAssignmentRepository.findPublishedAssignmentsForUserInRange(String, LocalDate, LocalDate)` — already exists.
- Produces: `PrayerAssignmentService.findOwnConflicts(String userId, LocalDate from, LocalDate to) : List<ConflictingAssignmentDTO>`.

- [ ] **Step 1: Create the DTO**

```java
package com.prayerroster.service.dto;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import java.time.LocalDate;

/** An assignment that a proposed unavailability would collide with. */
public record ConflictingAssignmentDTO(LocalDate date, PrayerAssignmentRole role) {
    public static ConflictingAssignmentDTO from(PrayerAssignment assignment) {
        return new ConflictingAssignmentDTO(assignment.getSession().getDate(), assignment.getRole());
    }
}
```

- [ ] **Step 2: Write the failing service test**

Add to `PrayerAssignmentServiceTest`:

```java
@Test
void findOwnConflicts_returnsPublishedAssignmentsInsideTheProposedRange() {
    PrayerAssignment assignment = assignmentOn(LocalDate.parse("2026-09-16"), PrayerAssignmentRole.MODERATOR);
    when(prayerAssignmentRepository.findPublishedAssignmentsForUserInRange("sub-1", LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-18")))
        .thenReturn(List.of(assignment));

    List<ConflictingAssignmentDTO> conflicts = service.findOwnConflicts(
        "sub-1",
        LocalDate.parse("2026-09-14"),
        LocalDate.parse("2026-09-18")
    );

    assertThat(conflicts).singleElement().satisfies(c -> {
        assertThat(c.date()).isEqualTo(LocalDate.parse("2026-09-16"));
        assertThat(c.role()).isEqualTo(PrayerAssignmentRole.MODERATOR);
    });
}

@Test
void findOwnConflicts_returnsEmptyWhenNothingCollides() {
    when(prayerAssignmentRepository.findPublishedAssignmentsForUserInRange("sub-1", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02")))
        .thenReturn(List.of());

    assertThat(service.findOwnConflicts("sub-1", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"))).isEmpty();
}
```

Reuse whatever fixture the file already has for building a `PrayerAssignment` with a session date; if none exists, add an `assignmentOn(LocalDate, PrayerAssignmentRole)` helper that builds a `PrayerSession`, sets its date, and attaches it to the assignment.

- [ ] **Step 3: Run it to make sure it fails**

Run: `./mvnw -o test -Dtest=PrayerAssignmentServiceTest`
Expected: FAIL — `findOwnConflicts` does not exist.

- [ ] **Step 4: Implement it**

```java
    /**
     * Assignments a proposed unavailability would collide with. Read-only and deliberately separate
     * from submission: without it the UI can only react after the backend has already flagged
     * sessions and started rescheduling, which is exactly the surprise the warning exists to prevent.
     */
    @Transactional(readOnly = true)
    public List<ConflictingAssignmentDTO> findOwnConflicts(String userId, LocalDate from, LocalDate to) {
        return prayerAssignmentRepository
            .findPublishedAssignmentsForUserInRange(userId, from, to)
            .stream()
            .map(ConflictingAssignmentDTO::from)
            .toList();
    }
```

- [ ] **Step 5: Run it to make sure it passes**

Run: `./mvnw -o test -Dtest=PrayerAssignmentServiceTest`
Expected: PASS

- [ ] **Step 6: Add the endpoint with its test**

In `MeAvailabilityResource` — it will need `PrayerAssignmentService` and `SecurityUtils` if it does not already resolve the current user; copy whatever pattern the file already uses for the current user id:

```java
    @GetMapping("/conflicts")
    public List<ConflictingAssignmentDTO> getConflicts(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return prayerAssignmentService.findOwnConflicts(currentUserId(), from, to);
    }
```

Add to `MeAvailabilityResourceTest`:

```java
@Test
void getConflicts_returnsCollidingAssignments() throws Exception {
    when(prayerAssignmentService.findOwnConflicts("sub-1", LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-18")))
        .thenReturn(List.of(new ConflictingAssignmentDTO(LocalDate.parse("2026-09-16"), PrayerAssignmentRole.MODERATOR)));

    mockMvc
        .perform(get("/api/me/availability/conflicts").param("from", "2026-09-14").param("to", "2026-09-18"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].role").value("MODERATOR"));
}
```

- [ ] **Step 7: Run the whole suite and the gate**

Run: `./mvnw -o verify -DskipITs`
Expected: PASS, coverage gate green.

- [ ] **Step 8: Commit**

```bash
git add src/main src/test
git commit -m "Add an availability conflict preview

Reuses the existing published-assignments-in-range query. Lets the UI warn
before submission rather than after rescheduling has already started."
```

---

### Task 6: Permission catalogue cleanup and member visibility

**Files:**
- Modify: `src/main/resources/config/permissions.json`
- Modify: `src/main/java/com/prayerroster/service/RbacSeedService.java`
- Create: `src/main/resources/config/liquibase/changelog/20260826000000_removed_unused_permissions.xml`
- Modify: `src/main/resources/config/liquibase/master.xml`
- Test: `src/test/java/com/prayerroster/service/RbacSeedServiceTest.java`

- [ ] **Step 1: Remove the five abandoned permission codes**

Delete these entries from `permissions.json`:

```
ROSTER_PUBLISH        publishing is automatic on a feasible solve; an explicit action could only
                      ever publish a roster that failed to solve
NOTIFICATION_VIEW     /api/me/notifications is self-service and correctly ungated
PERMISSION_CREATE     the catalogue is code-defined and re-seeded at boot
PERMISSION_UPDATE
PERMISSION_DELETE
```

`NOTIFICATION_VIEW` also appears in `ADMIN_DEFAULT_PERMISSIONS` in `RbacSeedService` — remove it there too, or the seeder will look up a code that no longer exists (it resolves silently to nothing, but leaving it is misleading).

- [ ] **Step 2: Give USER the weekly rhythm**

In `RbacSeedService.run`, change the `USER` seeding:

```java
        // PRAYER_CONFIG_VIEW only: a member sees which days have prayer and which need a preacher,
        // never who is serving - /api/prayer-sessions stays behind ROSTER_VIEW. Without it a
        // member's calendar is one highlighted day in an empty month. See section 10.
        seedRole(RoleNames.USER, "Utilisateur standard - accès à ses propres données uniquement", List.of("PRAYER_CONFIG_VIEW"));
```

- [ ] **Step 3: Write the changelog that removes the orphans**

`RbacSeedService` upserts by code and never deletes, so removing entries from the JSON leaves rows behind in an existing database.

```xml
<?xml version="1.0" encoding="utf-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="20260826000000-1" author="prayer-roster">
        <comment>Remove permissions abandoned in Sprint 11; role_permission rows go first (FK).</comment>
        <sql>
            delete from role_permission where permission_id in (
                select id from permission where code in
                ('ROSTER_PUBLISH','NOTIFICATION_VIEW','PERMISSION_CREATE','PERMISSION_UPDATE','PERMISSION_DELETE')
            );
        </sql>
        <sql>
            delete from permission where code in
            ('ROSTER_PUBLISH','NOTIFICATION_VIEW','PERMISSION_CREATE','PERMISSION_UPDATE','PERMISSION_DELETE');
        </sql>
    </changeSet>
</databaseChangeLog>
```

Register it in `master.xml` after the Sprint 10 includes.

- [ ] **Step 4: Update the seeding test**

`RbacSeedServiceTest` stubs `permissionRepository.findByCode(...)`, so `PRAYER_CONFIG_VIEW` needs a
stub or the role saves with an empty set and the assertion fails for the wrong reason. Add:

```java
@Test
void run_seedsUserRoleWithTheWeeklyConfigurationReadPermissionOnly() {
    Permission configView = new Permission();
    configView.setId(1L);
    configView.setCode("PRAYER_CONFIG_VIEW");
    when(roleRepository.findByName(RoleNames.USER)).thenReturn(Optional.empty());
    when(permissionRepository.findByCode("PRAYER_CONFIG_VIEW")).thenReturn(Optional.of(configView));

    service.seedRole(RoleNames.USER, "Utilisateur standard", List.of("PRAYER_CONFIG_VIEW"));

    ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
    verify(roleRepository).save(captor.capture());
    assertThat(captor.getValue().getPermissions()).extracting(Permission::getCode).containsExactly("PRAYER_CONFIG_VIEW");
}
```

Match the existing file's mock names and `service` field when pasting.

- [ ] **Step 5: Run the whole suite and the gate**

Run: `./mvnw -o verify -DskipITs`
Expected: PASS, coverage gate green.

- [ ] **Step 6: Commit**

```bash
git add src/main src/test
git commit -m "Retire five abandoned permissions and let members see the weekly rhythm

USER gains PRAYER_CONFIG_VIEW so a member's calendar can show which days
have prayer while assignments stay private. Five permissions that will
never have endpoints are removed from the catalogue, with a changelog
deleting the orphaned rows an existing database would keep."
```

---

### Task 7: Documentation and live verification

**Files:**
- Modify: `docs/phase1-architecture.md`, `docs/sprint-roadmap.md`, `CLAUDE.md`

- [ ] **Step 1: Amend architecture section 10**

Record the callback's two branches, the handoff (opaque, single-use, 60s, never a token in a URL), the `FRONTEND_BASE_URL` variable, and that `USER` now holds `PRAYER_CONFIG_VIEW`.

- [ ] **Step 2: Amend architecture section 9**

Record the new admin surface, the guards on role mutation, and that every role write evicts the whole authorities cache.

- [ ] **Step 3: Add the Sprint 11 roadmap entry**

Follow the existing style: what landed, the decisions behind it, and the live-verification evidence. State the five retired permissions and why. **Do not run prettier over `docs/`** — it is outside the `prettier:check` glob and eats spaces adjacent to code spans.

- [ ] **Step 4: Update CLAUDE.md**

Document the two callback branches, `/api/auth/exchange`, `FRONTEND_BASE_URL`, and `evictAll()` on role writes.

- [ ] **Step 5: Live-verify against a real Postgres**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./mvnw -o package -DskipTests
GOOGLE_CLIENT_ID=… GOOGLE_CLIENT_SECRET=… GOOGLE_REDIRECT_URI=… \
  FRONTEND_BASE_URL=http://localhost:3000 \
  java -jar target/*.jar --spring.profiles.active=dev \
  --spring.docker.compose.enabled=false --spring.mail.host=localhost --spring.mail.port=1025
```

Confirm, recording evidence for the roadmap:

1. The changelog applies and the five permission rows are gone.
2. With `FRONTEND_BASE_URL` set, the callback returns **302** to `/auth/callback?handoff=…` and the `Location` header contains **no token**.
3. `POST /api/auth/exchange` returns the token; replaying the same handoff returns 400.
4. Unsetting `FRONTEND_BASE_URL` restores the JSON branch.
5. Editing a role's permissions changes an affected user's access on the **next request**, not 60 seconds later.
6. `GET /api/rosters/{id}/generations` returns real solver scores.

- [ ] **Step 6: Commit**

```bash
git add docs CLAUDE.md
git commit -m "Document Sprint 11"
```
