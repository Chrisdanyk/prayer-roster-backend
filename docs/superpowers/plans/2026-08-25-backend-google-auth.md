# Backend-Owned Google Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the backend obtain a Google ID token by itself, and let only invited, verified, active people authenticate.

**Architecture:** The backend becomes a confidential OAuth client: it builds Google's authorization URL, then exchanges the returned code for an ID token server-side. Token _validation_ is untouched — the returned token is an ordinary Google ID token flowing through the existing resource-server pipeline. Admission (verified email, invited, active) is enforced at one choke point in `UserProvisioningService`, which throws instead of returning a user, so no `Authentication` is produced at all.

**Tech Stack:** Java 17, Spring Boot 3.4.5, Spring Security OAuth2 Resource Server, Spring Data JPA, PostgreSQL, Liquibase, Caffeine, JUnit 5 + Mockito + MockMvc standalone, JaCoCo.

**Spec:** `docs/superpowers/specs/2026-08-25-backend-google-auth-design.md`

## Global Constraints

- Run every command from `backend/`. Java is pinned to **17**.
- **100% line and branch coverage** on new `service` and `web/rest` classes — `./mvnw verify` fails otherwise. `config`, `domain`, `repository`, `service/dto`, and `web/rest/errors` are excluded by JaCoCo.
- **Never** use a role-name check. Gate endpoints with `@PreAuthorize("hasAuthority('PERM_X')")`.
- `ApplicationProperties` is `@ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)`. **Every new `application.*` key must be added to that class** or the context will not start.
- All user-facing text goes in `src/main/resources/i18n/messages_fr.properties` (default) and `messages_en.properties`. Never hardcode strings in Java.
- DTOs are Java records with a static `from(entity)` factory. No MapStruct mappers exist.
- Layering is enforced by ArchUnit (`TechnicalStructureTest`): config → web → service → security → repository → domain, one direction only.
- Liquibase changelogs are hand-written and must be registered in `master.xml`. `ddl-auto` is `none`. IDs use the shared `sequence_generator` sequence with `allocationSize = 50`.
- Secrets are never logged. Never log an authorization code or an ID token at any level.
- Unit tests only — a full `@SpringBootTest` cannot boot in this codebase (Timefold scans for `ConstraintProvider` implementations and finds the test double). Use Mockito and standalone MockMvc.
- Run `npm run prettier:format` before committing if `node_modules` is installed; otherwise formatting is not enforced locally.

---

### Task 1: Google profile image on User

**Files:**

- Create: `src/main/resources/config/liquibase/changelog/20260827000000_added_column_User_imageUrl.xml`
- Modify: `src/main/resources/config/liquibase/master.xml`
- Modify: `src/main/java/com/prayerroster/domain/User.java`
- Modify: `src/main/java/com/prayerroster/security/oauth2/GoogleIdentity.java`
- Modify: `src/main/java/com/prayerroster/service/UserProvisioningService.java`
- Modify: `src/main/java/com/prayerroster/service/dto/UserDTO.java`
- Test: `src/test/java/com/prayerroster/security/oauth2/GoogleIdentityTest.java`
- Test: `src/test/java/com/prayerroster/service/UserProvisioningServiceTest.java`
- Test: `src/test/java/com/prayerroster/web/rest/UserResourceTest.java` (constructor arity)

**Interfaces:**

- Produces: `GoogleIdentity.imageUrl()`, `User.getImageUrl()` / `setImageUrl(String)`, `UserDTO.imageUrl()`.

- [ ] **Step 1: Write the failing test for the claim**

Add to `GoogleIdentityTest`:

```java
@Test
void fromClaims_readsPictureClaim() {
    GoogleIdentity identity = GoogleIdentity.fromClaims(
        Map.of("sub", "sub-1", "email", "jean@example.com", "given_name", "Jean", "family_name", "Dupont", "picture", "https://lh3.googleusercontent.com/a/abc123")
    );

    assertThat(identity.imageUrl()).isEqualTo("https://lh3.googleusercontent.com/a/abc123");
}

@Test
void fromClaims_toleratesMissingPictureClaim() {
    GoogleIdentity identity = GoogleIdentity.fromClaims(Map.of("sub", "sub-1", "email", "jean@example.com"));

    assertThat(identity.imageUrl()).isNull();
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./mvnw test -Dtest=GoogleIdentityTest`
Expected: FAIL — `imageUrl()` does not exist.

- [ ] **Step 3: Add the claim**

Replace the record declaration in `GoogleIdentity.java`:

```java
public record GoogleIdentity(String sub, String email, String firstName, String lastName, String imageUrl) {
    public static GoogleIdentity fromClaims(Map<String, Object> claims) {
        return new GoogleIdentity(
            (String) claims.get(StandardClaimNames.SUB),
            (String) claims.get(StandardClaimNames.EMAIL),
            (String) claims.get(StandardClaimNames.GIVEN_NAME),
            (String) claims.get(StandardClaimNames.FAMILY_NAME),
            (String) claims.get(StandardClaimNames.PICTURE)
        );
    }
}
```

Fix every existing `new GoogleIdentity(...)` call in the test sources by appending a fifth argument (`null` where the image is irrelevant). Find them with `grep -rn "new GoogleIdentity(" src/test`.

- [ ] **Step 4: Run it to make sure it passes**

Run: `./mvnw test -Dtest=GoogleIdentityTest`
Expected: PASS

- [ ] **Step 5: Add the column to the entity**

In `User.java`, after the `lastName` field:

```java
    @Size(max = 512)
    @Column(name = "image_url", length = 512)
    private String imageUrl;
```

And after `setLastName`:

```java
    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
```

- [ ] **Step 6: Write the Liquibase changelog**

Create `20260827000000_added_column_User_imageUrl.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="20260827000000-1" author="prayer-roster">
        <addColumn tableName="app_user">
            <column name="image_url" type="varchar(512)"/>
        </addColumn>
    </changeSet>
</databaseChangeLog>
```

Register it in `master.xml` immediately above the `jhipster-needle-liquibase-add-changelog` comment:

```xml
    <include file="config/liquibase/changelog/20260827000000_added_column_User_imageUrl.xml" relativeToChangelogFile="false"/>
```

- [ ] **Step 7: Write the failing provisioning tests**

Add to `UserProvisioningServiceTest`, which already provides an `IDENTITY` constant, an
`existingUser()` fixture, a `roleWithName(String)` fixture, and a real (not mocked)
`applicationProperties`.

```java
@Test
void provisionOrRefresh_storesImageUrlOnCreate() {
    GoogleIdentity identity = new GoogleIdentity("sub-1", "jean@example.com", "Jean", "Dupont", "https://img/1.png");
    when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.empty());
    when(roleRepository.findByNameWithPermissions(RoleNames.USER)).thenReturn(Optional.of(roleWithName(RoleNames.USER)));
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User created = service.provisionOrRefresh(identity);

    assertThat(created.getImageUrl()).isEqualTo("https://img/1.png");
}

@Test
void provisionOrRefresh_updatesImageUrlWhenGoogleAvatarChanged() {
    User existing = existingUser();
    existing.setImageUrl("https://img/old.png");
    GoogleIdentity identity = new GoogleIdentity("sub-1", existing.getEmail(), existing.getFirstName(), existing.getLastName(), "https://img/new.png");
    when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.of(existing));
    when(userRepository.save(existing)).thenReturn(existing);

    User refreshed = service.provisionOrRefresh(identity);

    assertThat(refreshed.getImageUrl()).isEqualTo("https://img/new.png");
    verify(userRepository).save(existing);
}
```

- [ ] **Step 8: Run them to make sure they fail**

Run: `./mvnw test -Dtest=UserProvisioningServiceTest`
Expected: FAIL — image is never set.

- [ ] **Step 9: Handle the image in both provisioning paths**

In `UserProvisioningService.refreshProfileIfChanged`, add before the `return`:

```java
        if (!Objects.equals(user.getImageUrl(), identity.imageUrl())) {
            user.setImageUrl(identity.imageUrl());
            changed = true;
        }
```

In `buildAndSaveNewUser`, after `user.setLastName(...)`:

```java
        user.setImageUrl(identity.imageUrl());
```

- [ ] **Step 10: Run them to make sure they pass**

Run: `./mvnw test -Dtest=UserProvisioningServiceTest`
Expected: PASS

- [ ] **Step 11: Expose it on the DTO**

In `UserDTO.java`, add `String imageUrl` after `lastName` in both the record header and the `from` factory:

```java
public record UserDTO(
    String id,
    String email,
    String firstName,
    String lastName,
    String imageUrl,
    boolean active,
    boolean canModerate,
    boolean canPreach,
    String roleName
) {
    public static UserDTO from(User user) {
        return new UserDTO(
            user.getId(),
            user.getEmail(),
            user.getFirstName(),
            user.getLastName(),
            user.getImageUrl(),
            user.isActive(),
            user.isCanModerate(),
            user.isCanPreach(),
            user.getRole().getName()
        );
    }
}
```

Then fix the four positional constructor calls in `UserResourceTest.java` (lines 43, 85, 98, 113) by inserting a fifth argument, e.g. `"https://img/1.png"`, after `"Dupont"`.

- [ ] **Step 12: Run the full suite**

Run: `./mvnw test`
Expected: PASS, no compilation errors.

- [ ] **Step 13: Commit**

```bash
git add src/main src/test
git commit -m "Store the Google profile image on the user"
```

---

### Task 2: Fail closed on unverified email and deactivated users

This closes a live bug: an inactive user currently loses their authorities but stays authenticated, so a deactivated person keeps full access to every `/api/me/**` endpoint.

**Files:**

- Modify: `src/main/java/com/prayerroster/security/oauth2/GoogleIdentity.java`
- Modify: `src/main/java/com/prayerroster/service/UserProvisioningService.java`
- Modify: `src/main/java/com/prayerroster/security/DynamicAuthoritiesService.java`
- Test: `src/test/java/com/prayerroster/security/oauth2/GoogleIdentityTest.java`
- Test: `src/test/java/com/prayerroster/service/UserProvisioningServiceTest.java`
- Test: `src/test/java/com/prayerroster/security/DynamicAuthoritiesServiceTest.java`

**Interfaces:**

- Consumes: `GoogleIdentity` from Task 1.
- Produces: `GoogleIdentity.emailVerified()`; `UserProvisioningService.provisionOrRefresh` now throws `org.springframework.security.oauth2.server.resource.InvalidBearerTokenException` instead of returning a user for a rejected identity.

- [ ] **Step 1: Write the failing claim test**

Add to `GoogleIdentityTest`:

```java
@Test
void fromClaims_readsEmailVerifiedClaim() {
    GoogleIdentity identity = GoogleIdentity.fromClaims(Map.of("sub", "sub-1", "email", "jean@example.com", "email_verified", true));

    assertThat(identity.emailVerified()).isTrue();
}

@Test
void fromClaims_treatsMissingEmailVerifiedAsFalse() {
    GoogleIdentity identity = GoogleIdentity.fromClaims(Map.of("sub", "sub-1", "email", "jean@example.com"));

    assertThat(identity.emailVerified()).isFalse();
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./mvnw test -Dtest=GoogleIdentityTest`
Expected: FAIL — `emailVerified()` does not exist.

- [ ] **Step 3: Add the claim, failing closed**

```java
public record GoogleIdentity(String sub, String email, boolean emailVerified, String firstName, String lastName, String imageUrl) {
    public static GoogleIdentity fromClaims(Map<String, Object> claims) {
        return new GoogleIdentity(
            (String) claims.get(StandardClaimNames.SUB),
            (String) claims.get(StandardClaimNames.EMAIL),
            Boolean.TRUE.equals(claims.get(StandardClaimNames.EMAIL_VERIFIED)),
            (String) claims.get(StandardClaimNames.GIVEN_NAME),
            (String) claims.get(StandardClaimNames.FAMILY_NAME),
            (String) claims.get(StandardClaimNames.PICTURE)
        );
    }
}
```

`Boolean.TRUE.equals` treats a missing, null, or non-boolean claim as unverified — deliberate, so a malformed token cannot pass. Update every `new GoogleIdentity(...)` in the tests to pass `true` as the third argument (these represent verified accounts).

- [ ] **Step 4: Run it to make sure it passes**

Run: `./mvnw test -Dtest=GoogleIdentityTest`
Expected: PASS

- [ ] **Step 5: Write the failing denial tests**

Add to `UserProvisioningServiceTest`:

```java
@Test
void provisionOrRefresh_rejectsUnverifiedEmail() {
    GoogleIdentity unverified = new GoogleIdentity("sub-1", "jean@example.com", false, "Jean", "Dupont", null);

    assertThatThrownBy(() -> service.provisionOrRefresh(unverified))
        .isInstanceOf(InvalidBearerTokenException.class)
        .hasMessageContaining("not verified");

    verifyNoInteractions(userRepository);
}

@Test
void provisionOrRefresh_rejectsDeactivatedUser() {
    User existing = existingUser();
    existing.setActive(false);
    when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.provisionOrRefresh(IDENTITY)).isInstanceOf(InvalidBearerTokenException.class).hasMessageContaining("deactivated");

    verify(userRepository, never()).save(any());
}
```

- [ ] **Step 6: Run them to make sure they fail**

Run: `./mvnw test -Dtest=UserProvisioningServiceTest`
Expected: FAIL — no exception is thrown.

- [ ] **Step 7: Enforce both rules at the choke point**

Replace `provisionOrRefresh` in `UserProvisioningService`:

```java
    public User provisionOrRefresh(GoogleIdentity identity) {
        if (!identity.emailVerified()) {
            throw new InvalidBearerTokenException("Google account email is not verified");
        }
        Optional<User> existing = userRepository.findByIdWithRoleAndPermissions(identity.sub());
        if (existing.isPresent()) {
            User user = existing.get();
            if (!user.isActive()) {
                throw new InvalidBearerTokenException("User account is deactivated");
            }
            return refreshProfileIfChanged(user, identity);
        }
        return createUserSafely(identity);
    }
```

Add the imports `java.util.Optional` and `org.springframework.security.oauth2.server.resource.InvalidBearerTokenException`.

Update the class Javadoc to state that an unverified or deactivated identity is denied outright rather than resolving to an empty authority set.

- [ ] **Step 8: Run them to make sure they pass**

Run: `./mvnw test -Dtest=UserProvisioningServiceTest`
Expected: PASS

- [ ] **Step 9: Delete the branch that is now unreachable**

`DynamicAuthoritiesService.computeAuthorities` can no longer receive an inactive user — provisioning throws first. Delete these lines from `computeAuthorities`:

```java
        if (!user.isActive()) {
            return Set.of();
        }
```

Delete the test that covered it: `resolveAuthorities_returnsNoAuthoritiesForInactiveUser` in `DynamicAuthoritiesServiceTest` (around line 69).

Leaving the branch in place would fail the coverage gate as an uncovered branch. Deleting unreachable code rather than testing around it matches the practice established in sprints 3, 5, and 7.

- [ ] **Step 10: Run the full suite and the coverage gate**

Run: `./mvnw verify -Dmaven.test.failure.ignore=true`
Expected: tests PASS and the `coverage-check` execution passes.

- [ ] **Step 11: Commit**

```bash
git add src/main src/test
git commit -m "Deny authentication for unverified emails and deactivated users

An inactive user previously lost their authorities but stayed
authenticated, so a deactivated person retained full access to every
/api/me endpoint. Denying at provisioning means no Authentication is
produced at all, which makes the inactive branch in
DynamicAuthoritiesService unreachable - deleted rather than tested
around."
```

---

### Task 3: The allowlist entity

**Files:**

- Create: `src/main/java/com/prayerroster/domain/AllowedEmail.java`
- Create: `src/main/java/com/prayerroster/repository/AllowedEmailRepository.java`
- Create: `src/main/resources/config/liquibase/changelog/20260827000100_added_entity_AllowedEmail.xml`
- Modify: `src/main/resources/config/liquibase/master.xml`

**Interfaces:**

- Produces: `AllowedEmail` (getId, getEmail, setEmail); `AllowedEmailRepository.existsByEmailIgnoringCase(String)`, plus inherited `findAll`, `save`, `existsById`, `deleteById`.

There is no test step here: `domain` and `repository` are excluded from the coverage gate and hold no logic. Task 4 exercises the repository through a mock.

- [ ] **Step 1: Create the entity**

```java
package com.prayerroster.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An email address permitted to authenticate. Admission is invite-only: an identity with no local
 * {@link User} row may only be provisioned if its email appears here. Governs first admission only -
 * once a User exists, {@code User.active} governs access. See
 * docs/phase1-architecture.md section 10.
 */
@Entity
@Table(name = "allowed_email")
public class AllowedEmail extends AbstractAuditingEntity<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator", sequenceName = "sequence_generator", allocationSize = 50)
    @Column(name = "id")
    private Long id;

    /** Always stored lowercased - see AllowedEmailService. */
    @NotNull
    @Email
    @Size(max = 254)
    @Column(name = "email", length = 254, nullable = false, unique = true)
    private String email;

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
```

- [ ] **Step 2: Create the repository**

```java
package com.prayerroster.repository;

import com.prayerroster.domain.AllowedEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AllowedEmailRepository extends JpaRepository<AllowedEmail, Long> {
    /**
     * Explicit query rather than a derived one. Sprint 7 shipped a derived-query name that failed at
     * repository initialisation because Spring Data split it on a reserved keyword, and mocked
     * repositories never surface that - so anything non-trivial is spelled out here.
     */
    @Query("select count(a) > 0 from AllowedEmail a where lower(a.email) = lower(:email)")
    boolean existsByEmailIgnoringCase(@Param("email") String email);
}
```

- [ ] **Step 3: Write the changelog**

Create `20260827000100_added_entity_AllowedEmail.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="20260827000100-1" author="prayer-roster">
        <createTable tableName="allowed_email">
            <column name="id" type="bigint">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="email" type="varchar(254)">
                <constraints nullable="false" unique="true" uniqueConstraintName="ux_allowed_email_email"/>
            </column>
            <column name="created_by" type="varchar(50)">
                <constraints nullable="false"/>
            </column>
            <column name="created_date" type="${datetimeType}"/>
            <column name="last_modified_by" type="varchar(50)"/>
            <column name="last_modified_date" type="${datetimeType}"/>
        </createTable>
    </changeSet>
</databaseChangeLog>
```

Register it in `master.xml` directly after the Task 1 include.

- [ ] **Step 4: Verify it compiles**

Run: `./mvnw test-compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main
git commit -m "Add the allowed_email table for invite-only admission"
```

---

### Task 4: Enforce invite-only admission

**Files:**

- Modify: `src/main/java/com/prayerroster/service/UserProvisioningService.java`
- Test: `src/test/java/com/prayerroster/service/UserProvisioningServiceTest.java`

**Interfaces:**

- Consumes: `AllowedEmailRepository.existsByEmailIgnoringCase(String)` (Task 3); the denial behaviour from Task 2.
- Produces: `UserProvisioningService` now takes `AllowedEmailRepository` as a fifth constructor argument.

- [ ] **Step 1: Write the failing tests**

Add to `UserProvisioningServiceTest`. The constructor gains a parameter, so update the existing `@BeforeEach` setup to pass a new `@Mock AllowedEmailRepository allowedEmailRepository`.

```java
@Test
void provisionOrRefresh_rejectsUninvitedEmailAndCreatesNoRow() {
    when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.empty());
    when(allowedEmailRepository.existsByEmailIgnoringCase("jean@example.com")).thenReturn(false);

    assertThatThrownBy(() -> service.provisionOrRefresh(IDENTITY)).isInstanceOf(InvalidBearerTokenException.class).hasMessageContaining("not invited");

    verify(userRepository, never()).save(any());
}

@Test
void provisionOrRefresh_admitsInvitedEmail() {
    when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.empty());
    when(allowedEmailRepository.existsByEmailIgnoringCase("jean@example.com")).thenReturn(true);
    when(roleRepository.findByNameWithPermissions(RoleNames.USER)).thenReturn(Optional.of(roleWithName(RoleNames.USER)));
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User created = service.provisionOrRefresh(IDENTITY);

    assertThat(created.getRole().getName()).isEqualTo(RoleNames.USER);
}

@Test
void provisionOrRefresh_admitsBootstrapSuperAdminWithoutAnInvite() {
    applicationProperties.getSecurity().setInitialSuperAdminEmail("JEAN@EXAMPLE.COM");
    when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.empty());
    when(roleRepository.findByNameWithPermissions(RoleNames.SUPER_ADMIN)).thenReturn(Optional.of(roleWithName(RoleNames.SUPER_ADMIN)));
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User created = service.provisionOrRefresh(IDENTITY);

    assertThat(created.getRole().getName()).isEqualTo(RoleNames.SUPER_ADMIN);
    verifyNoInteractions(allowedEmailRepository);
}

@Test
void provisionOrRefresh_doesNotRecheckTheAllowlistForAnExistingUser() {
    User existing = existingUser();
    when(userRepository.findByIdWithRoleAndPermissions("sub-1")).thenReturn(Optional.of(existing));

    service.provisionOrRefresh(IDENTITY);

    verifyNoInteractions(allowedEmailRepository);
}
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./mvnw test -Dtest=UserProvisioningServiceTest`
Expected: FAIL — compilation error, the constructor has no `AllowedEmailRepository`.

- [ ] **Step 3: Add the admission check**

Add the field, constructor parameter, and assignment for `AllowedEmailRepository`, then add the guard to `provisionOrRefresh` where the user does not yet exist:

```java
        return createUserSafely(requireAdmitted(identity));
```

and the method:

```java
    /**
     * The allowlist governs first admission only. Once a User row exists, {@code active} governs -
     * removing an allowlist entry never locks out someone who already signed in. The bootstrap
     * super-admin email is an implicit entry so the very first sign-in works against an empty
     * database. See docs/phase1-architecture.md section 10.
     */
    private GoogleIdentity requireAdmitted(GoogleIdentity identity) {
        String bootstrapEmail = applicationProperties.getSecurity().getInitialSuperAdminEmail();
        boolean bootstrap = bootstrapEmail != null && bootstrapEmail.equalsIgnoreCase(identity.email());
        if (!bootstrap && !allowedEmailRepository.existsByEmailIgnoringCase(identity.email())) {
            throw new InvalidBearerTokenException("Email address is not invited");
        }
        return identity;
    }
```

- [ ] **Step 4: Repair the five existing tests this breaks**

Every existing test that provisions a **new** user now hits the allowlist check and fails with "not
invited". Add this stub to each of them:

```java
    when(allowedEmailRepository.existsByEmailIgnoringCase("jean@example.com")).thenReturn(true);
```

The affected tests in `UserProvisioningServiceTest` are:

- `provisionOrRefresh_createsNewUserWithUserRoleByDefault`
- `provisionOrRefresh_doesNotPromoteWhenBootstrapEmailDoesNotMatch`
- `provisionOrRefresh_fallsBackToReadingWinnerRowWhenConcurrentCreateLosesRace`
- `provisionOrRefresh_rethrowsWhenRaceLostButWinnerRowStillNotFound`
- `provisionOrRefresh_throwsWhenRoleNotYetSeeded`

`provisionOrRefresh_promotesConfiguredBootstrapEmailToSuperAdmin` needs no change — the bootstrap
email bypasses the allowlist by design, and that is exactly what the new
`admitsBootstrapSuperAdminWithoutAnInvite` test asserts.

- [ ] **Step 5: Run them to make sure they pass**

Run: `./mvnw test -Dtest=UserProvisioningServiceTest`
Expected: PASS

- [ ] **Step 6: Run the whole suite and the gate**

Run: `./mvnw verify -Dmaven.test.failure.ignore=true`
Expected: PASS, coverage gate green.

- [ ] **Step 7: Commit**

```bash
git add src/main src/test
git commit -m "Restrict authentication to invited email addresses"
```

---

### Task 5: Allowlist management API

**Files:**

- Create: `src/main/java/com/prayerroster/service/dto/AllowedEmailDTO.java`
- Create: `src/main/java/com/prayerroster/service/dto/InviteEmailRequest.java`
- Create: `src/main/java/com/prayerroster/service/AllowedEmailService.java`
- Create: `src/main/java/com/prayerroster/web/rest/AllowedEmailResource.java`
- Modify: `src/main/java/com/prayerroster/service/RbacSeedService.java`
- Test: `src/test/java/com/prayerroster/service/AllowedEmailServiceTest.java`
- Test: `src/test/java/com/prayerroster/web/rest/AllowedEmailResourceTest.java`
- Test: `src/test/java/com/prayerroster/service/RbacSeedServiceTest.java`

**Interfaces:**

- Consumes: `AllowedEmail`, `AllowedEmailRepository` (Task 3).
- Produces: `AllowedEmailService.invite(String) : AllowedEmailDTO`, `findAll() : List<AllowedEmailDTO>`, `delete(Long)`; `AllowedEmailDTO(Long id, String email, Instant createdDate)`.

- [ ] **Step 1: Create the DTOs**

`AllowedEmailDTO.java`:

```java
package com.prayerroster.service.dto;

import com.prayerroster.domain.AllowedEmail;
import java.time.Instant;

public record AllowedEmailDTO(Long id, String email, Instant createdDate) {
    public static AllowedEmailDTO from(AllowedEmail allowedEmail) {
        return new AllowedEmailDTO(allowedEmail.getId(), allowedEmail.getEmail(), allowedEmail.getCreatedDate());
    }
}
```

`InviteEmailRequest.java`:

```java
package com.prayerroster.service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteEmailRequest(@NotBlank @Email @Size(max = 254) String email) {}
```

- [ ] **Step 2: Write the failing service tests**

Create `AllowedEmailServiceTest.java`:

```java
package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.prayerroster.domain.AllowedEmail;
import com.prayerroster.repository.AllowedEmailRepository;
import com.prayerroster.service.dto.AllowedEmailDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AllowedEmailServiceTest {

    @Mock
    private AllowedEmailRepository allowedEmailRepository;

    private AllowedEmailService service;

    @BeforeEach
    void setUp() {
        service = new AllowedEmailService(allowedEmailRepository);
    }

    @Test
    void invite_storesTheEmailLowercasedAndTrimmed() {
        when(allowedEmailRepository.existsByEmailIgnoringCase("jean@example.com")).thenReturn(false);
        when(allowedEmailRepository.save(any(AllowedEmail.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.invite("  Jean@Example.COM  ");

        ArgumentCaptor<AllowedEmail> captor = ArgumentCaptor.forClass(AllowedEmail.class);
        verify(allowedEmailRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("jean@example.com");
    }

    @Test
    void invite_rejectsADuplicate() {
        when(allowedEmailRepository.existsByEmailIgnoringCase("jean@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.invite("jean@example.com")).isInstanceOf(BadRequestAlertException.class);

        verify(allowedEmailRepository, never()).save(any());
    }

    @Test
    void findAll_mapsToDtos() {
        AllowedEmail entity = new AllowedEmail();
        entity.setId(1L);
        entity.setEmail("jean@example.com");
        when(allowedEmailRepository.findAll()).thenReturn(List.of(entity));

        List<AllowedEmailDTO> result = service.findAll();

        assertThat(result).singleElement().extracting(AllowedEmailDTO::email).isEqualTo("jean@example.com");
    }

    @Test
    void delete_removesAnExistingEntry() {
        when(allowedEmailRepository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(allowedEmailRepository).deleteById(1L);
    }

    @Test
    void delete_rejectsAnUnknownId() {
        when(allowedEmailRepository.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(EntityNotFoundException.class);

        verify(allowedEmailRepository, never()).deleteById(any());
    }
}
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `./mvnw test -Dtest=AllowedEmailServiceTest`
Expected: FAIL — `AllowedEmailService` does not exist.

- [ ] **Step 4: Write the service**

```java
package com.prayerroster.service;

import com.prayerroster.domain.AllowedEmail;
import com.prayerroster.repository.AllowedEmailRepository;
import com.prayerroster.service.dto.AllowedEmailDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the invite allowlist. Removing an entry does not revoke someone who has already signed in -
 * the allowlist governs first admission only, and {@code User.active} governs thereafter. See
 * docs/phase1-architecture.md section 10.
 */
@Service
@Transactional
public class AllowedEmailService {

    private static final String ENTITY_NAME = "allowedEmail";

    private final AllowedEmailRepository allowedEmailRepository;

    public AllowedEmailService(AllowedEmailRepository allowedEmailRepository) {
        this.allowedEmailRepository = allowedEmailRepository;
    }

    @Transactional(readOnly = true)
    public List<AllowedEmailDTO> findAll() {
        return allowedEmailRepository.findAll().stream().map(AllowedEmailDTO::from).toList();
    }

    public AllowedEmailDTO invite(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (allowedEmailRepository.existsByEmailIgnoringCase(normalized)) {
            throw new BadRequestAlertException("This email address is already invited", ENTITY_NAME, "duplicateEmail");
        }
        AllowedEmail allowedEmail = new AllowedEmail();
        allowedEmail.setEmail(normalized);
        return AllowedEmailDTO.from(allowedEmailRepository.save(allowedEmail));
    }

    public void delete(Long id) {
        if (!allowedEmailRepository.existsById(id)) {
            throw new EntityNotFoundException("Allowed email not found: " + id);
        }
        allowedEmailRepository.deleteById(id);
    }
}
```

- [ ] **Step 5: Run them to make sure they pass**

Run: `./mvnw test -Dtest=AllowedEmailServiceTest`
Expected: PASS

- [ ] **Step 6: Write the failing resource tests**

Create `AllowedEmailResourceTest.java`, following the standalone-MockMvc pattern used by `ReminderConfigurationResourceTest`:

```java
package com.prayerroster.web.rest;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.service.AllowedEmailService;
import com.prayerroster.service.dto.AllowedEmailDTO;
import com.prayerroster.service.dto.InviteEmailRequest;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AllowedEmailResourceTest {

    @Mock
    private AllowedEmailService allowedEmailService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new AllowedEmailResource(allowedEmailService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void getAllowedEmails_returnsTheList() throws Exception {
        when(allowedEmailService.findAll()).thenReturn(List.of(new AllowedEmailDTO(1L, "jean@example.com", null)));

        mockMvc.perform(get("/api/allowed-emails")).andExpect(status().isOk()).andExpect(jsonPath("$[0].email").value("jean@example.com"));
    }

    @Test
    void inviteEmail_returns201() throws Exception {
        when(allowedEmailService.invite("jean@example.com")).thenReturn(new AllowedEmailDTO(1L, "jean@example.com", null));

        mockMvc
            .perform(
                post("/api/allowed-emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new InviteEmailRequest("jean@example.com")))
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").value("jean@example.com"));
    }

    @Test
    void inviteEmail_returns400OnDuplicate() throws Exception {
        when(allowedEmailService.invite("jean@example.com")).thenThrow(new BadRequestAlertException("duplicate", "allowedEmail", "duplicateEmail"));

        mockMvc
            .perform(
                post("/api/allowed-emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new InviteEmailRequest("jean@example.com")))
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    void inviteEmail_returns400OnMalformedEmail() throws Exception {
        mockMvc
            .perform(
                post("/api/allowed-emails").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(new InviteEmailRequest("not-an-email")))
            )
            .andExpect(status().isBadRequest());

        verifyNoInteractions(allowedEmailService);
    }

    @Test
    void deleteAllowedEmail_returns204() throws Exception {
        mockMvc.perform(delete("/api/allowed-emails/{id}", 1L)).andExpect(status().isNoContent());

        verify(allowedEmailService).delete(1L);
    }
}
```

- [ ] **Step 7: Run them to make sure they fail**

Run: `./mvnw test -Dtest=AllowedEmailResourceTest`
Expected: FAIL — `AllowedEmailResource` does not exist.

- [ ] **Step 8: Write the resource**

```java
package com.prayerroster.web.rest;

import com.prayerroster.service.AllowedEmailService;
import com.prayerroster.service.dto.AllowedEmailDTO;
import com.prayerroster.service.dto.InviteEmailRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Manages who is permitted to authenticate. Deleting an entry does not lock out a person who has
 * already signed in - deactivate them via {@code PUT /api/users/{id}/status} instead.
 */
@RestController
@RequestMapping("/api/allowed-emails")
public class AllowedEmailResource {

    private final AllowedEmailService allowedEmailService;

    public AllowedEmailResource(AllowedEmailService allowedEmailService) {
        this.allowedEmailService = allowedEmailService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_USER_VIEW')")
    public List<AllowedEmailDTO> getAllowedEmails() {
        return allowedEmailService.findAll();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_USER_CREATE')")
    public ResponseEntity<AllowedEmailDTO> inviteEmail(@Valid @RequestBody InviteEmailRequest request) {
        return ResponseEntity.status(201).body(allowedEmailService.invite(request.email()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_USER_DELETE')")
    public ResponseEntity<Void> deleteAllowedEmail(@PathVariable Long id) {
        allowedEmailService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 9: Run them to make sure they pass**

Run: `./mvnw test -Dtest=AllowedEmailResourceTest`
Expected: PASS

- [ ] **Step 10: Let ADMIN invite**

In `RbacSeedService`, add `"USER_CREATE"` to `ADMIN_DEFAULT_PERMISSIONS`, immediately after `"USER_VIEW"`. Do **not** add `USER_DELETE` — removal stays with `SUPER_ADMIN`, and the same generic code would gate a future `DELETE /api/users/{id}`.

Read `RbacSeedServiceTest` and update any assertion that pins the exact ADMIN permission set or its size.

Note for the reviewer: `seedRole` skips roles that already exist, so this affects only fresh databases. An existing database needs `USER_CREATE` granted to `ADMIN` through the API.

- [ ] **Step 11: Run the whole suite and the gate**

Run: `./mvnw verify -Dmaven.test.failure.ignore=true`
Expected: PASS, coverage gate green.

- [ ] **Step 12: Commit**

```bash
git add src/main src/test
git commit -m "Add the allowlist management API"
```

---

### Task 6: Google OAuth configuration and OIDC discovery

**Files:**

- Modify: `src/main/java/com/prayerroster/config/ApplicationProperties.java`
- Modify: `src/main/resources/config/application.yml`
- Modify: `src/main/resources/config/application-dev.yml`
- Create: `src/main/java/com/prayerroster/service/GoogleDiscoveryService.java`
- Test: `src/test/java/com/prayerroster/service/GoogleDiscoveryServiceTest.java`

**Interfaces:**

- Produces: `ApplicationProperties.getGoogle()` returning a `Google` with `getClientId()`, `getClientSecret()`, `getRedirectUri()`; `GoogleDiscoveryService.authorizationEndpoint() : String` and `tokenEndpoint() : String`.

- [ ] **Step 1: Add the properties class**

In `ApplicationProperties`, add a field, getter, and nested class alongside the existing `Liquibase` and `Security` ones:

```java
    private final Google google = new Google();

    public Google getGoogle() {
        return google;
    }

    /** Credentials for the server-side authorization-code exchange - see docs/phase1-architecture.md section 10. */
    public static class Google {

        private String clientId;
        private String clientSecret;
        private String redirectUri;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }
    }
```

This class is bound with `ignoreUnknownFields = false`, so the yml keys below cannot be added without it.

- [ ] **Step 2: Add the yml keys**

In `application.yml`, under the existing `application:` document at the bottom, alongside `security:`:

```yaml
google:
  client-id: ${GOOGLE_CLIENT_ID:}
  client-secret: ${GOOGLE_CLIENT_SECRET:}
  # Must exactly match a redirect URI registered in the Google Cloud console.
  redirect-uri: ${GOOGLE_REDIRECT_URI:}
```

In `application-dev.yml`, append a dev default so a local run works without extra environment setup:

```yaml
application:
  google:
    redirect-uri: ${GOOGLE_REDIRECT_URI:http://localhost:8080/api/auth/google/callback}
```

- [ ] **Step 3: Write the failing discovery test**

Create `GoogleDiscoveryServiceTest.java`:

```java
package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GoogleDiscoveryServiceTest {

    private static final String ISSUER = "https://accounts.google.com";
    private static final String METADATA =
        """
        {"authorization_endpoint":"https://accounts.google.com/o/oauth2/v2/auth","token_endpoint":"https://oauth2.googleapis.com/token"}
        """;

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
        server.expect(requestTo(ISSUER + "/.well-known/openid-configuration")).andRespond(withSuccess(METADATA, MediaType.APPLICATION_JSON));

        assertThat(service.authorizationEndpoint()).isEqualTo("https://accounts.google.com/o/oauth2/v2/auth");
    }

    @Test
    void tokenEndpoint_isReadFromTheDiscoveryDocument() {
        server.expect(requestTo(ISSUER + "/.well-known/openid-configuration")).andRespond(withSuccess(METADATA, MediaType.APPLICATION_JSON));

        assertThat(service.tokenEndpoint()).isEqualTo("https://oauth2.googleapis.com/token");
    }

    @Test
    void theDocumentIsFetchedOnlyOnce() {
        server
            .expect(ExpectedCount.once(), requestTo(ISSUER + "/.well-known/openid-configuration"))
            .andRespond(withSuccess(METADATA, MediaType.APPLICATION_JSON));

        service.authorizationEndpoint();
        service.tokenEndpoint();

        server.verify();
    }

    @Test
    void aFailedFetchIsReportedAsAnUpstreamFailure() {
        server.expect(requestTo(ISSUER + "/.well-known/openid-configuration")).andRespond(withServerError());

        assertThatThrownBy(() -> service.tokenEndpoint()).isInstanceOf(GoogleAuthenticationException.class);
    }
}
```

- [ ] **Step 4: Run it to make sure it fails**

Run: `./mvnw test -Dtest=GoogleDiscoveryServiceTest`
Expected: FAIL — the class does not exist.

- [ ] **Step 5: Create the upstream-failure exception**

Create `src/main/java/com/prayerroster/web/rest/errors/GoogleAuthenticationException.java`:

```java
package com.prayerroster.web.rest.errors;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Google itself failed or answered unusably. Maps to 502 via JHipster's {@code ExceptionTranslator}. */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class GoogleAuthenticationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GoogleAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }

    public GoogleAuthenticationException(String message) {
        super(message);
    }
}
```

- [ ] **Step 6: Write the discovery service**

```java
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
 * provider can be substituted later. The document is immutable in practice, so it is fetched once
 * and held for the life of the application.
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
            throw new GoogleAuthenticationException("Google discovery document has no " + key);
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
```

- [ ] **Step 7: Run it to make sure it passes**

Run: `./mvnw test -Dtest=GoogleDiscoveryServiceTest`
Expected: PASS

- [ ] **Step 8: Add a test for the missing-key branch**

The `value == null` branch is not yet covered, and the gate demands every branch. Add:

```java
@Test
void aDocumentMissingTheTokenEndpointIsAnUpstreamFailure() {
    server.expect(requestTo(ISSUER + "/.well-known/openid-configuration")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> service.tokenEndpoint()).isInstanceOf(GoogleAuthenticationException.class).hasMessageContaining("token_endpoint");
}
```

- [ ] **Step 9: Run it to make sure it passes**

Run: `./mvnw test -Dtest=GoogleDiscoveryServiceTest`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add src/main src/test
git commit -m "Add Google OAuth client properties and OIDC discovery"
```

---

### Task 7: Authorization request store (state + PKCE)

**Files:**

- Create: `src/main/java/com/prayerroster/service/AuthorizationRequestStore.java`
- Test: `src/test/java/com/prayerroster/service/AuthorizationRequestStoreTest.java`

**Interfaces:**

- Produces: `AuthorizationRequestStore.create() : PendingAuthorization` where `PendingAuthorization` is a record `(String state, String codeVerifier, String codeChallenge)`; `consume(String state) : String` returning the code verifier and throwing `BadRequestAlertException` if the state is unknown, expired, or already used.

- [ ] **Step 1: Write the failing tests**

Create `AuthorizationRequestStoreTest.java`:

```java
package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prayerroster.service.AuthorizationRequestStore.PendingAuthorization;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorizationRequestStoreTest {

    private AuthorizationRequestStore store;

    @BeforeEach
    void setUp() {
        store = new AuthorizationRequestStore();
    }

    @Test
    void create_producesAUniqueStateEachTime() {
        assertThat(store.create().state()).isNotEqualTo(store.create().state());
    }

    @Test
    void create_derivesAnS256ChallengeFromTheVerifier() {
        PendingAuthorization pending = store.create();

        // Base64url, unpadded, SHA-256 output is always 43 characters.
        assertThat(pending.codeChallenge()).hasSize(43).doesNotContain("=", "+", "/");
        assertThat(pending.codeVerifier()).isNotEqualTo(pending.codeChallenge());
    }

    @Test
    void consume_returnsTheVerifierForAKnownState() {
        PendingAuthorization pending = store.create();

        assertThat(store.consume(pending.state())).isEqualTo(pending.codeVerifier());
    }

    @Test
    void consume_rejectsAReplayedState() {
        PendingAuthorization pending = store.create();
        store.consume(pending.state());

        assertThatThrownBy(() -> store.consume(pending.state())).isInstanceOf(BadRequestAlertException.class);
    }

    @Test
    void consume_rejectsAnUnknownState() {
        assertThatThrownBy(() -> store.consume("never-issued")).isInstanceOf(BadRequestAlertException.class);
    }

    @Test
    void create_failsLoudlyIfTheDigestAlgorithmIsUnavailable() {
        AuthorizationRequestStore broken = new AuthorizationRequestStore("NO-SUCH-ALGORITHM");

        assertThatThrownBy(broken::create).isInstanceOf(IllegalStateException.class);
    }
}
```

That last test exists for a specific reason. `MessageDigest.getInstance` throws a checked
`NoSuchAlgorithmException` that cannot occur on a real JVM, and an uncovered `catch` block is
uncovered **lines** — which fails the 100% gate. Rather than leaving dead code the gate rejects, the
algorithm name is a constructor parameter with a production default, so the failure path is real and
reachable from a test.

- [ ] **Step 2: Run them to make sure they fail**

Run: `./mvnw test -Dtest=AuthorizationRequestStoreTest`
Expected: FAIL — the class does not exist.

- [ ] **Step 3: Write the store**

```java
package com.prayerroster.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.stereotype.Service;

/**
 * Holds the {@code state} and PKCE verifier of an in-flight authorization request. Entries expire
 * after five minutes and are removed on first use, so a replayed callback fails - the same
 * short-TTL-cache approach {@code DynamicAuthoritiesService} uses.
 */
@Service
public class AuthorizationRequestStore {

    private static final String ENTITY_NAME = "authentication";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Cache<String, String> pendingByState = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(10_000).build();
    private final String digestAlgorithm;

    public AuthorizationRequestStore() {
        this("SHA-256");
    }

    /**
     * The algorithm is a parameter only so the "algorithm missing" failure path is reachable from a
     * test - {@code MessageDigest.getInstance} declares a checked exception that cannot occur on a
     * real JVM, and an untestable catch block would fail the coverage gate. Production always uses
     * the no-argument constructor.
     */
    AuthorizationRequestStore(String digestAlgorithm) {
        this.digestAlgorithm = digestAlgorithm;
    }

    public PendingAuthorization create() {
        String state = randomToken();
        String codeVerifier = randomToken();
        pendingByState.put(state, codeVerifier);
        return new PendingAuthorization(state, codeVerifier, challengeFor(codeVerifier));
    }

    /** Single use: the entry is removed atomically, so a second callback with the same state fails. */
    public String consume(String state) {
        String codeVerifier = pendingByState.asMap().remove(state);
        if (codeVerifier == null) {
            throw new BadRequestAlertException("Unknown, expired, or already used authorization state", ENTITY_NAME, "invalidState");
        }
        return codeVerifier;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }

    private String challengeFor(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance(digestAlgorithm).digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return encoder.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Digest algorithm unavailable: " + digestAlgorithm, e);
        }
    }

    public record PendingAuthorization(String state, String codeVerifier, String codeChallenge) {}
}
```

- [ ] **Step 4: Run them to make sure they pass**

Run: `./mvnw test -Dtest=AuthorizationRequestStoreTest`
Expected: PASS

- [ ] **Step 5: Check coverage of this class**

Run: `./mvnw verify -Dmaven.test.failure.ignore=true`
Expected: coverage gate green, including the catch block, which the bogus-algorithm test reaches.

- [ ] **Step 6: Commit**

```bash
git add src/main src/test
git commit -m "Add the authorization request store with PKCE support"
```

---

### Task 8: Token exchange

**Files:**

- Create: `src/main/java/com/prayerroster/service/dto/GoogleTokenResponse.java`
- Create: `src/main/java/com/prayerroster/service/GoogleTokenExchangeService.java`
- Test: `src/test/java/com/prayerroster/service/GoogleTokenExchangeServiceTest.java`

**Interfaces:**

- Consumes: `GoogleDiscoveryService.tokenEndpoint()` (Task 6); `ApplicationProperties.getGoogle()` (Task 6).
- Produces: `GoogleTokenExchangeService.exchange(String code, String codeVerifier) : GoogleTokenResponse`; `GoogleTokenResponse(String idToken, long expiresIn)`.

- [ ] **Step 1: Create the response DTO**

```java
package com.prayerroster.service.dto;

/** What the caller receives after a successful code exchange. */
public record GoogleTokenResponse(String idToken, long expiresIn) {}
```

- [ ] **Step 2: Write the failing tests**

Create `GoogleTokenExchangeServiceTest.java`:

```java
package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.GoogleAuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=authorization_code")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("code_verifier=verifier-1")))
            .andRespond(withSuccess("{\"id_token\":\"eyJhbGciOi\",\"expires_in\":3599}", MediaType.APPLICATION_JSON));

        GoogleTokenResponse response = service.exchange("code-1", "verifier-1");

        assertThat(response.idToken()).isEqualTo("eyJhbGciOi");
        assertThat(response.expiresIn()).isEqualTo(3599L);
    }

    @Test
    void exchange_reportsAGoogleRejectionAsAnUpstreamFailure() {
        when(discoveryService.tokenEndpoint()).thenReturn(TOKEN_ENDPOINT);
        server.expect(requestTo(TOKEN_ENDPOINT)).andRespond(withBadRequest().body("{\"error\":\"invalid_grant\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.exchange("code-1", "verifier-1")).isInstanceOf(GoogleAuthenticationException.class);
    }

    @Test
    void exchange_rejectsAResponseWithNoIdToken() {
        when(discoveryService.tokenEndpoint()).thenReturn(TOKEN_ENDPOINT);
        server.expect(requestTo(TOKEN_ENDPOINT)).andRespond(withSuccess("{\"expires_in\":3599}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.exchange("code-1", "verifier-1")).isInstanceOf(GoogleAuthenticationException.class).hasMessageContaining("id_token");
    }

    @Test
    void exchange_defaultsExpiryWhenGoogleOmitsIt() {
        when(discoveryService.tokenEndpoint()).thenReturn(TOKEN_ENDPOINT);
        server.expect(requestTo(TOKEN_ENDPOINT)).andRespond(withSuccess("{\"id_token\":\"eyJhbGciOi\"}", MediaType.APPLICATION_JSON));

        assertThat(service.exchange("code-1", "verifier-1").expiresIn()).isZero();
    }
}
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `./mvnw test -Dtest=GoogleTokenExchangeServiceTest`
Expected: FAIL — the class does not exist.

- [ ] **Step 4: Write the exchange service**

```java
package com.prayerroster.service;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.GoogleAuthenticationException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Exchanges an authorization code for a Google ID token. The client secret and the code never leave
 * the server, and neither the code nor the resulting token is ever logged.
 */
@Service
public class GoogleTokenExchangeService {

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
            // Deliberately does not include Google's response body, which can echo the code back.
            throw new GoogleAuthenticationException("Google rejected the authorization code exchange", e);
        }
    }
}
```

- [ ] **Step 5: Run them to make sure they pass**

Run: `./mvnw test -Dtest=GoogleTokenExchangeServiceTest`
Expected: PASS

- [ ] **Step 6: Verify the coverage gate**

Run: `./mvnw verify -Dmaven.test.failure.ignore=true`
Expected: PASS. If the `body == null` branch is uncovered, add a test responding with `withSuccess("", MediaType.APPLICATION_JSON)`.

- [ ] **Step 7: Commit**

```bash
git add src/main src/test
git commit -m "Exchange the Google authorization code for an ID token"
```

---

### Task 9: The authentication endpoints

**Files:**

- Create: `src/main/java/com/prayerroster/service/dto/AuthorizationUrlResponse.java`
- Create: `src/main/java/com/prayerroster/service/GoogleAuthenticationService.java`
- Create: `src/main/java/com/prayerroster/web/rest/GoogleAuthenticationResource.java`
- Modify: `src/main/java/com/prayerroster/config/SecurityConfiguration.java`
- Modify: `src/main/resources/i18n/messages_fr.properties`, `messages_en.properties`
- Test: `src/test/java/com/prayerroster/service/GoogleAuthenticationServiceTest.java`
- Test: `src/test/java/com/prayerroster/web/rest/GoogleAuthenticationResourceTest.java`

**Interfaces:**

- Consumes: `AuthorizationRequestStore.create()` / `consume(String)` (Task 7); `GoogleTokenExchangeService.exchange(String, String)` (Task 8); `GoogleDiscoveryService.authorizationEndpoint()` (Task 6).
- Produces: `GoogleAuthenticationService.authorizationUrl() : AuthorizationUrlResponse`, `completeLogin(String code, String state) : GoogleTokenResponse`; `AuthorizationUrlResponse(String authorizationUrl, String state)`.

- [ ] **Step 1: Create the response DTO**

```java
package com.prayerroster.service.dto;

public record AuthorizationUrlResponse(String authorizationUrl, String state) {}
```

- [ ] **Step 2: Write the failing service tests**

Create `GoogleAuthenticationServiceTest.java`:

```java
package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.service.AuthorizationRequestStore.PendingAuthorization;
import com.prayerroster.service.dto.AuthorizationUrlResponse;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GoogleAuthenticationServiceTest {

    @Mock
    private GoogleDiscoveryService discoveryService;

    @Mock
    private AuthorizationRequestStore requestStore;

    @Mock
    private GoogleTokenExchangeService tokenExchangeService;

    private GoogleAuthenticationService service;

    @BeforeEach
    void setUp() {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getGoogle().setClientId("client-id");
        properties.getGoogle().setRedirectUri("https://app.example.com/api/auth/google/callback");
        service = new GoogleAuthenticationService(discoveryService, requestStore, tokenExchangeService, properties);
    }

    @Test
    void authorizationUrl_containsEveryRequiredParameter() {
        when(discoveryService.authorizationEndpoint()).thenReturn("https://accounts.google.com/o/oauth2/v2/auth");
        when(requestStore.create()).thenReturn(new PendingAuthorization("state-1", "verifier-1", "challenge-1"));

        AuthorizationUrlResponse response = service.authorizationUrl();

        assertThat(response.state()).isEqualTo("state-1");
        assertThat(response.authorizationUrl())
            .startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
            .contains("client_id=client-id")
            .contains("response_type=code")
            .contains("scope=openid%20email%20profile")
            .contains("state=state-1")
            .contains("code_challenge=challenge-1")
            .contains("code_challenge_method=S256")
            .contains("redirect_uri=https%3A%2F%2Fapp.example.com%2Fapi%2Fauth%2Fgoogle%2Fcallback");
    }

    @Test
    void completeLogin_consumesTheStateAndExchangesTheCode() {
        when(requestStore.consume("state-1")).thenReturn("verifier-1");
        when(tokenExchangeService.exchange("code-1", "verifier-1")).thenReturn(new GoogleTokenResponse("id-token", 3599));

        GoogleTokenResponse response = service.completeLogin("code-1", "state-1");

        assertThat(response.idToken()).isEqualTo("id-token");
        verify(requestStore).consume("state-1");
    }

    @Test
    void completeLogin_propagatesAnInvalidState() {
        when(requestStore.consume("bad-state")).thenThrow(new BadRequestAlertException("invalid", "authentication", "invalidState"));

        assertThatThrownBy(() -> service.completeLogin("code-1", "bad-state")).isInstanceOf(BadRequestAlertException.class);

        verifyNoInteractions(tokenExchangeService);
    }
}
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `./mvnw test -Dtest=GoogleAuthenticationServiceTest`
Expected: FAIL — the class does not exist.

- [ ] **Step 4: Write the service**

```java
package com.prayerroster.service;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.service.AuthorizationRequestStore.PendingAuthorization;
import com.prayerroster.service.dto.AuthorizationUrlResponse;
import com.prayerroster.service.dto.GoogleTokenResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Drives the authorization-code flow: builds the URL the user signs in at, then turns the code
 * Google hands back into an ID token. See docs/phase1-architecture.md section 10.
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
            .queryParam("client_id", google.getClientId())
            .queryParam("redirect_uri", google.getRedirectUri())
            .queryParam("response_type", "code")
            .queryParam("scope", SCOPE)
            .queryParam("state", pending.state())
            .queryParam("code_challenge", pending.codeChallenge())
            .queryParam("code_challenge_method", "S256")
            .build()
            .encode()
            .toUriString();
        return new AuthorizationUrlResponse(url, pending.state());
    }

    public GoogleTokenResponse completeLogin(String code, String state) {
        String codeVerifier = requestStore.consume(state);
        return tokenExchangeService.exchange(code, codeVerifier);
    }
}
```

- [ ] **Step 5: Run them to make sure they pass**

Run: `./mvnw test -Dtest=GoogleAuthenticationServiceTest`
Expected: PASS

- [ ] **Step 6: Write the failing resource tests**

Create `GoogleAuthenticationResourceTest.java`:

```java
package com.prayerroster.web.rest;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.service.GoogleAuthenticationService;
import com.prayerroster.service.dto.AuthorizationUrlResponse;
import com.prayerroster.service.dto.GoogleTokenResponse;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.ExceptionTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class GoogleAuthenticationResourceTest {

    @Mock
    private GoogleAuthenticationService googleAuthenticationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GoogleAuthenticationResource(googleAuthenticationService))
            .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
    }

    @Test
    void getAuthorizationUrl_returnsTheUrlAndState() throws Exception {
        when(googleAuthenticationService.authorizationUrl()).thenReturn(new AuthorizationUrlResponse("https://accounts.google.com/o/oauth2/v2/auth?x=1", "state-1"));

        mockMvc
            .perform(get("/api/auth/google/url"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("state-1"))
            .andExpect(jsonPath("$.authorizationUrl").value("https://accounts.google.com/o/oauth2/v2/auth?x=1"));
    }

    @Test
    void callback_returnsTheIdToken() throws Exception {
        when(googleAuthenticationService.completeLogin("code-1", "state-1")).thenReturn(new GoogleTokenResponse("id-token", 3599));

        mockMvc
            .perform(get("/api/auth/google/callback").param("code", "code-1").param("state", "state-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.idToken").value("id-token"))
            .andExpect(jsonPath("$.expiresIn").value(3599));
    }

    @Test
    void callback_returns400WhenGoogleReportsAnError() throws Exception {
        mockMvc.perform(get("/api/auth/google/callback").param("error", "access_denied").param("state", "state-1")).andExpect(status().isBadRequest());

        verifyNoInteractions(googleAuthenticationService);
    }

    @Test
    void callback_returns400WhenTheCodeIsMissing() throws Exception {
        mockMvc.perform(get("/api/auth/google/callback").param("state", "state-1")).andExpect(status().isBadRequest());

        verifyNoInteractions(googleAuthenticationService);
    }

    @Test
    void callback_returns400OnAnInvalidState() throws Exception {
        when(googleAuthenticationService.completeLogin("code-1", "bad")).thenThrow(new BadRequestAlertException("invalid", "authentication", "invalidState"));

        mockMvc.perform(get("/api/auth/google/callback").param("code", "code-1").param("state", "bad")).andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 7: Run them to make sure they fail**

Run: `./mvnw test -Dtest=GoogleAuthenticationResourceTest`
Expected: FAIL — the class does not exist.

- [ ] **Step 8: Write the resource**

```java
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
 * how a caller obtains the credential every other endpoint requires. Neither the authorization code
 * nor the resulting token is logged, and the token is returned in the response body rather than a
 * redirect URL so it never reaches browser history or an access log.
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
```

- [ ] **Step 9: Run them to make sure they pass**

Run: `./mvnw test -Dtest=GoogleAuthenticationResourceTest`
Expected: PASS

- [ ] **Step 10: Open the endpoints in the security config**

In `SecurityConfiguration.filterChain`, add above the `/api/**` matcher (order matters — the first match wins):

```java
                    .requestMatchers(mvc.pattern("/api/auth/google/url")).permitAll()
                    .requestMatchers(mvc.pattern("/api/auth/google/callback")).permitAll()
```

- [ ] **Step 11: Add the error messages**

Append to `messages_fr.properties`:

```properties
# Authentification
error.invalidState=Demande d'authentification inconnue ou expirée. Veuillez recommencer.
error.authorizationDenied=La connexion Google a été refusée.
error.missingCode=Le retour de Google ne contient pas de code d'autorisation.
error.duplicateEmail=Cette adresse e-mail est déjà invitée.
```

Append the English equivalents to `messages_en.properties`:

```properties
# Authentication
error.invalidState=Unknown or expired authentication request. Please start again.
error.authorizationDenied=The Google sign-in was refused.
error.missingCode=Google's callback carried no authorization code.
error.duplicateEmail=This email address is already invited.
```

- [ ] **Step 12: Run the whole suite and the gate**

Run: `./mvnw verify -Dmaven.test.failure.ignore=true`
Expected: PASS, coverage gate green, `TechnicalStructureTest` green.

- [ ] **Step 13: Commit**

```bash
git add src/main src/test
git commit -m "Add the backend-owned Google sign-in endpoints"
```

---

### Task 10: Documentation and live verification

**Files:**

- Modify: `docs/phase1-architecture.md` (sections 9 and 10)
- Modify: `docs/sprint-roadmap.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Rewrite architecture section 10**

Replace the "Google Authentication Architecture" bullets so they describe what now exists: the backend is a confidential OAuth client that performs the authorization-code exchange with PKCE; `GOOGLE_CLIENT_SECRET` and `GOOGLE_REDIRECT_URI` are required; the ID token is returned in the response body and still travels as a Bearer credential; there are no refresh tokens; admission is invite-only via `allowed_email`, with `initial-super-admin-email` as an implicit entry. Remove the sentence flagging `GOOGLE_CLIENT_SECRET` as probably-unneeded — that assumption is now resolved. Delete assumption 2 from the "Assumptions flagged for review" list at the end of the document for the same reason.

- [ ] **Step 2: Amend architecture section 9**

Add a sentence recording that an unverified, uninvited, or deactivated identity is now denied at authentication rather than resolving to an empty authority set, and that this closed a real gap where a deactivated user retained access to `/api/me/**`.

- [ ] **Step 3: Add the Sprint 10 roadmap entry**

Follow the existing entries' style: what landed, the decisions behind it, the bug found and fixed, and the live-verification evidence. Record the pre-existing deactivation gap explicitly, and note that `ADMIN` gained `USER_CREATE` only for fresh databases.

- [ ] **Step 4: Update CLAUDE.md**

The "Auth: stateless Google resource server" section currently says a separate frontend does the sign-in. Rewrite it to state that the backend performs the exchange, that admission is invite-only, and that authentication is denied outright for unverified, uninvited, or inactive identities. Add `GOOGLE_CLIENT_SECRET` and `GOOGLE_REDIRECT_URI` to the environment-variables section.

- [ ] **Step 5: Live-verify against a real Postgres**

A full `@SpringBootTest` cannot boot here, so verify the packaged jar as every prior sprint did.

```bash
npm run docker:db:up
./mvnw -Pprod clean verify -DskipTests
GOOGLE_CLIENT_ID=… GOOGLE_CLIENT_SECRET=… \
  GOOGLE_REDIRECT_URI=http://localhost:8080/api/auth/google/callback \
  INITIAL_SUPER_ADMIN_EMAIL=you@example.com \
  java -jar target/*.jar --spring.profiles.active=prod
```

Confirm each of these, recording the evidence for the roadmap entry:

1. `GET /api/auth/google/url` returns a URL; opening it and signing in lands on the callback, which returns an `idToken`.
2. That token authenticates a real request: `curl -H "Authorization: Bearer <idToken>" localhost:8080/api/account` returns your account.
3. The same token passes a `PERM_*`-gated endpoint for a permitted role and is refused with 403 for a role lacking the permission. **This is the first end-to-end verification of the authorization pipeline** — the gap Sprint 7 conceded.
4. Signing in with an uninvited Google account returns 401, and `select count(*) from app_user` is unchanged.
5. Deactivating a user and then calling `/api/me/availability` with their token returns 401 — the bug fix, against behaviour that succeeds on `develop` today.
6. Replaying the same callback URL a second time returns 400.

- [ ] **Step 6: Commit**

```bash
git add docs CLAUDE.md
git commit -m "Document the backend-owned Google authentication flow"
```
