# Backend-Owned Google Authentication & Admission Control

Status: approved design, not yet implemented
Supersedes: `docs/phase1-architecture.md` section 10 (Google Authentication Architecture)

## Problem

The backend is a stateless OAuth2 resource server with no way to obtain a token. It validates
Google ID tokens but never issues or fetches one, and no frontend exists yet. The practical
consequences:

- The running application cannot be exercised at all. No curl, no Postman, no Swagger request can
  get past `.authenticated()`.
- Authorization has never been verified over a real HTTP round trip. Sprint 7 explicitly conceded
  this, falling back to standalone MockMvc for `ReminderConfigurationResource`'s permission gating
  because forging a signed Google ID token outside a browser is impractical.
- Section 10 left open whether `GOOGLE_CLIENT_SECRET` is needed server-side. It is: this design
  resolves that assumption by making the backend a confidential client.

A second, independent problem surfaced while designing this, and cannot be shipped around:
**nothing restricts who may authenticate.** Any Google account completing the flow is provisioned
as an active user. Today that is masked only by the absence of a way to get a token; this design
removes that accident, so admission control ships with it.

## Decisions

Four decisions were taken before this document, and constrain everything below.

1. **The backend performs the authorization-code exchange.** It becomes a confidential OAuth
   client. This reverses section 10's "no server-side code exchange" and makes
   `GOOGLE_CLIENT_SECRET` required.
2. **The client receives the Google ID token in the response body.** The client secret and the
   authorization code stay server-side; the token continues to travel as a `Bearer` credential
   exactly as today. Nothing about _token validation_ changes — `AudienceValidator`,
   `DynamicJwtAuthenticationConverter`, and the `PERM_*` pipeline all run exactly as they do for a
   frontend-issued token, which is the point: the flow exercises production code rather than a
   parallel test-only path. (Admission control, below, does modify what happens _after_ validation
   succeeds; that is a separate concern from the exchange.)

   Rejected: an HttpOnly session cookie (strict BFF). It is more secure, but it would end
   `SessionCreationPolicy.STATELESS`, require CSRF protection to return, introduce session storage,
   and leave two authentication mechanisms to keep correct. Moving to it later is contained once
   the exchange exists.

   Rejected: the backend signing its own JWTs. It would add key management and rotation to replace
   a token already validated correctly, and its usual benefit — embedding claims — is something
   this design deliberately avoids, since `DynamicAuthoritiesService` resolves permissions from the
   database per request so that a role change takes effect within seconds.

3. **No refresh tokens.** Google ID tokens last roughly an hour; renewal means re-running the
   authorization redirect, which is silent when the user still holds a live Google session. Nothing
   sensitive is stored at rest, and no encryption, key management, or Google-side invalidation
   handling is needed. `POST /api/auth/refresh` can be added later without changing the endpoints
   defined here.
4. **Admission is invite-only.** An administrator adds an email to an allowlist before that person
   can sign in. Rejected: self-registration with admin approval (strangers still accumulate rows)
   and Google Workspace domain restriction via the `hd` claim (only viable if every member has an
   account on an organisation domain, which cannot be assumed).

## Scope

In scope: the two authentication endpoints, the allowlist and its management endpoints, admission
enforcement at the authentication choke point, the deactivation-lockout fix described below, and
the Google profile image.

Out of scope: refresh tokens, a browser-app landing or handoff code, rate limiting on the
authentication endpoints, and any admin UI. Removing an allowlist entry does not revoke an existing
user — see "Allowlist semantics".

## The authentication flow

```
GET  /api/auth/google/url
     -> generate state + PKCE verifier, store server-side (5 min TTL, single use)
     -> { authorizationUrl, state }

     ... the user opens authorizationUrl and signs in at Google ...

GET  /api/auth/google/callback?code=...&state=...
     -> consume state (must exist, be unexpired, and not have been used)
     -> POST code + client_secret + PKCE verifier to Google's token endpoint
     -> { idToken, expiresIn }
```

Both endpoints are `permitAll` in `SecurityConfiguration`, alongside the existing `/api/auth-info`.

Google's authorization and token endpoint URLs are **discovered from the issuer's OIDC metadata**
(`${issuer}/.well-known/openid-configuration`), not hardcoded. `JwtDecoders.fromOidcIssuerLocation`
already relies on that document, the existing `GOOGLE_ISSUER` override stays meaningful, and it
leaves the door open to pointing a dev profile at a mock provider later. The result is cached for
the lifetime of the application.

PKCE (S256) is used in addition to the client secret. The `state` value is single-use with a
five-minute TTL, so a replayed callback fails.

## Admission control

### The vulnerability

`UserProvisioningService.buildAndSaveNewUser` sets `active = true` unconditionally. Any Google
account that completes the flow is provisioned immediately. The `USER` role is seeded with no
permissions, so no `PERM_*`-gated endpoint is reachable — but `/api/me/**` is deliberately
authenticated-only, so a stranger can read `/api/account` and **write**: create, update, and delete
availability records, and change notification preferences. `canModerate` and `canPreach` default to
`false`, so `UserRepository.findAllEligibleActive()` can never draw a stranger into a roster; that
is the only reason this is not severe.

### The pre-existing bug this also fixes

`DynamicAuthoritiesService.computeAuthorities` returns an empty authority set for an inactive user.
That strips authorities but does not prevent authentication: the converter still constructs a
`JwtAuthenticationToken`, which is authenticated, so `.authenticated()` passes. **A deactivated user
retains full access to every self-service endpoint** under `/api/me/**`, and can still submit
availability. Sprint 6 treats deactivation as removal and triggers rescheduling on it, so the
lockout was intended to be complete. `DynamicAuthoritiesServiceTest:69` asserts the empty-authority
behaviour, which suggests the consequence went unnoticed rather than being deliberate.

### The fix: deny at the converter

Both problems share one choke point. `UserProvisioningService` gains admission checks and throws
`InvalidBearerTokenException` rather than returning a user, so no `Authentication` is produced at
all and Spring's `BearerTokenAuthenticationEntryPoint` returns 401. Denial rules:

| Condition                                 | Outcome                                      |
| ----------------------------------------- | -------------------------------------------- |
| `email_verified` is absent or false       | 401 — fail closed; nothing checks this today |
| No local user, email not on the allowlist | 401, and **no row is created**               |
| No local user, email on the allowlist     | provision as today (role `USER`, active)     |
| Local user exists and is inactive         | 401                                          |
| Local user exists and is active           | proceed                                      |

The `initial-super-admin-email` is treated as an implicit allowlist entry, so the first
administrator can sign in against an empty database.

Consequences to handle during implementation:

- `computeAuthorities`'s `!user.isActive()` branch becomes unreachable once denial moves upstream.
  Delete it rather than testing around it — the coverage gate will flag it, and this matches the
  established practice from sprints 3, 5, and 7.
- Denials are not cached. `DynamicAuthoritiesService`'s Caffeine cache stores authority sets; a
  thrown exception caches nothing, so a rejected identity costs one database read per attempt.
  That is correct — negative authorization decisions should not be cached — and the cost is bounded
  because the caller receives 401 and gains nothing by retrying.

### Allowlist semantics

`allowed_email` is a small table (`email` unique, plus the standard audit fields from
`AbstractAuditingEntity`). Emails are stored and compared lowercased, consistent with the existing
`equalsIgnoreCase` bootstrap check.

**The allowlist governs first admission only.** Once a `User` row exists, `active` governs access.
Deleting an allowlist entry for someone who has already signed in therefore does not lock them out;
deactivating them via `PUT /api/users/{id}/status` does. Keeping one mechanism per concern avoids
two overlapping revocation paths that can disagree. This is a genuine ambiguity, so the `DELETE`
endpoint's response and the API documentation must state it plainly.

A separate table is required rather than pre-creating `User` rows: `User.id` **is** the Google `sub`
claim, so the primary key is unknowable until first sign-in. This preserves that property, which
section 2 of the architecture document treats as a deliberate strength, and avoids touching any
foreign key.

Endpoints, using permissions that already exist in `permissions.json`:

| Endpoint                          | Permission         |
| --------------------------------- | ------------------ |
| `POST /api/allowed-emails`        | `PERM_USER_CREATE` |
| `GET /api/allowed-emails`         | `PERM_USER_VIEW`   |
| `DELETE /api/allowed-emails/{id}` | `PERM_USER_DELETE` |

`USER_CREATE` and `USER_DELETE` are currently unused, which suggests an invite mechanism was
anticipated. See "Open question" below regarding which roles hold them.

## Profile image

There is no image field on `User` at all; JHipster's stock `imageUrl` did not survive the
hand-written entity. This adds:

- `User.imageUrl` (`@Size(max = 512)`, column `image_url`, nullable) — Google's picture URLs are
  long, and the field is absent for accounts with no avatar.
- The `picture` claim on `GoogleIdentity`.
- Handling in **both** `buildAndSaveNewUser` and `refreshProfileIfChanged`, so a changed Google
  avatar propagates on the next request the same way a changed surname already does.
- The field on `UserDTO`.

## Components

Following existing layering so `TechnicalStructureTest` stays green:

| Class                                          | Layer                   | Responsibility                                            |
| ---------------------------------------------- | ----------------------- | --------------------------------------------------------- |
| `GoogleOAuthProperties`                        | `config`                | client id, client secret, redirect URI                    |
| `AuthorizationRequestStore`                    | `service`               | Caffeine, 5 min TTL, `state` -> PKCE verifier, single use |
| `GoogleTokenExchangeService`                   | `service`               | OIDC discovery + `RestClient` code exchange               |
| `GoogleAuthenticationResource`                 | `web/rest`              | the two endpoints                                         |
| `AllowedEmail` / `AllowedEmailRepository`      | `domain` / `repository` | the allowlist                                             |
| `AllowedEmailService` / `AllowedEmailResource` | `service` / `web/rest`  | allowlist management                                      |

Modified: `UserProvisioningService` (admission checks, image), `DynamicAuthoritiesService` (delete
the dead branch), `GoogleIdentity` (`picture`, `email_verified`), `SecurityConfiguration`
(`permitAll` for `/api/auth/**`), `User`, `UserDTO`, `ApplicationProperties`.

`RestClient` is available — the project is on Spring Boot 3.4.5.

## Configuration

New environment variables: `GOOGLE_CLIENT_SECRET` (now genuinely required) and
`GOOGLE_REDIRECT_URI`, which must exactly match a redirect URI registered in the Google Cloud
console.

`ApplicationProperties` is `@ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)`,
so **every new `application.*` key must be added to that class or the context will not start.**

The client secret has no default in any yml file and is never logged. Neither the authorization code
nor the ID token is logged at any level.

## Error handling

Through the existing `ExceptionTranslator` and `ProblemDetail` machinery, with `ErrorConstants` keys
and French messages in `messages_fr.properties`:

| Case                                             | Response                                      |
| ------------------------------------------------ | --------------------------------------------- |
| `state` unknown, expired, or already used        | 400                                           |
| Google redirects back with `error=access_denied` | 400                                           |
| Token exchange fails or returns no `id_token`    | 502                                           |
| Email not admitted, unverified, or user inactive | 401 (via the entry point, not the translator) |
| Allowlist email already present                  | 400                                           |

Google error payloads are never passed through to the caller verbatim.

## Testing

Unit tests in the established shape — Mockito with standalone MockMvc, no Spring context, no
database — with the exchange client mocked:

- `AuthorizationRequestStore`: TTL expiry, single-use consumption, unknown state.
- `GoogleTokenExchangeService`: discovery, successful exchange, Google error response, missing
  `id_token`.
- `GoogleAuthenticationResource`: URL generation, callback success, each failure branch.
- `UserProvisioningService`: every row of the denial table above, plus image set on create and
  refreshed on change.
- `AllowedEmailService` / `AllowedEmailResource`: add, list, delete, duplicate, and permission
  gating.
- `DynamicAuthoritiesServiceTest`: remove the now-unreachable inactive-user case.

New `service` and `web/rest` classes must reach 100% line and branch coverage or `verify` fails.
`GoogleOAuthProperties` sits in `config`, which JaCoCo already excludes.

### Live verification

The payoff. With a real Google OAuth client configured, boot the packaged jar against a real
Postgres (the established pattern — a full `@SpringBootTest` still cannot boot, per the roadmap's
environment notes) and confirm:

1. The flow returns a token that **authenticates a real HTTP request** to `/api/account`.
2. That token passes a `PERM_*`-gated endpoint for a permitted role and is rejected with 403 for a
   role lacking the permission — the first genuine end-to-end verification of the authorization
   pipeline, closing the gap Sprint 7 conceded.
3. An uninvited email is rejected with 401 **and creates no `app_user` row**.
4. A deactivated user is rejected with 401 on `/api/me/availability` — the bug fix, proven against
   the behaviour that exists today.
5. A replayed callback (same `state` twice) is rejected.

## Documentation to update

- `docs/phase1-architecture.md` section 10: rewritten. The backend is a confidential client;
  `GOOGLE_CLIENT_SECRET` is required; admission is invite-only.
- `docs/phase1-architecture.md` section 9: note that authentication is now denied outright for
  unadmitted or inactive identities, rather than resolving to an empty authority set.
- `CLAUDE.md`: the auth section, which currently states the frontend performs sign-in.
- `docs/sprint-roadmap.md`: a Sprint 10 entry.

## Open question

`RbacSeedService` seeds `ADMIN` with neither `USER_CREATE` nor `USER_DELETE`, so **by default only
`SUPER_ADMIN` can invite or remove people.** That may be intentional — Sprint 2 deliberately kept
`USER_ROLE_ASSIGN` away from `ADMIN` — or it may simply be that no endpoint used those permissions
until now. Note that `seedRole` skips roles that already exist, so changing the default set affects
only new installations; existing databases need the permission assigned through the API. Confirm
before implementation.
