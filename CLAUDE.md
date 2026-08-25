# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repo layout

This repository is `backend/` inside the `prayer-roster` workspace; the workspace root itself is not
a git repo, so sessions that start there must run everything below from `backend/`.

`docs/` holds the two design documents the code constantly cites in Javadoc:

- `docs/phase1-architecture.md` — the binding design decisions (domain model, RBAC, Timefold model,
  rescheduling, notifications). Javadoc references it by section number; keep it in sync.
- `docs/sprint-roadmap.md` — sprint-by-sprint history, the bugs found in each, and the local
  environment notes (Colima, Testcontainers, JaCoCo gotchas). Read before debugging build/test
  infrastructure.

## Commands

The build requires **JDK 17** (`pom.xml` pins it; the enforcer rejects anything outside 17–24). If
your default `java` is newer, every Maven command needs `JAVA_HOME` set, e.g.
`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

```bash
./mvnw                                      # run in dev profile (Postgres must be up)
npm run docker:db:up                        # Postgres on 5432 (docker:db:down to tear down)

./mvnw test                                 # unit tests only (surefire); the fast local loop
./mvnw test -Dtest=RosterServiceTest        # single test class
./mvnw test -Dtest=RosterServiceTest#methodName    # single test method
./mvnw verify -Dmaven.test.failure.ignore=true   # + integration tests + the JaCoCo coverage gate

npm run prettier:format                     # formats java/yml/json/md (prettier-plugin-java)
npm run backend:nohttp:test                 # checkstyle (nohttp rule)
./mvnw -Pprod clean verify                  # production jar
```

The architecture doc argues for Java 21; the pom is the truth. Node ≥22.15 is only needed for
prettier/husky.

`husky` + `lint-staged` run `prettier --write` on commit.

### Verification reality

`*Test` classes are Mockito/standalone-MockMvc unit tests and run under surefire. `*IT` classes run
under failsafe with Testcontainers, but **a full `@SpringBootTest` cannot boot in this codebase**:
Timefold's starter eagerly scans the classpath for `ConstraintProvider` implementations and refuses
to start when it finds more than one, and `SolverServiceTest` deliberately defines a second, broken
one. `src/main/resources/solverConfig.xml` pins the real provider by name but does not suppress that
scan. Every sprint therefore live-verified by **booting the packaged jar against a real Postgres**,
not via a JUnit IT. Do the same rather than trying to resurrect the IT suite.

The coverage gate is **100% line and branch** on hand-authored business logic, enforced at `verify`
(`haltOnFailure`). Its exclude list exists **twice** in `pom.xml` — a plugin-level
`<configuration><excludes>` (class-file globs, the one that actually filters the BUNDLE ratio) and a
rule-level `<rule><excludes>` (package names, silently ignored for BUNDLE). **Keep both in sync** when
adding an excluded package. The gate regularly surfaces genuinely unreachable branches; the
established response is to delete the dead code, not to test around it.

## Architecture

### Auth: backend-owned Google sign-in, stateless validation

JHipster's session/`oauth2Login`/client scaffolding was stripped out. `SecurityConfiguration` is
resource-server-only, stateless, CSRF disabled. `AudienceValidator` checks `aud == GOOGLE_CLIENT_ID`
(issuer-uri alone does not validate audience).

The backend **obtains tokens itself** as a confidential OAuth client: `GET /api/auth/google/url`
builds Google's authorization URL (PKCE S256, single-use five-minute `state`), and
`GET /api/auth/google/callback` exchanges the code server-side and returns the ID token in the
response body. Both are `permitAll`. Endpoint URLs come from the issuer's OIDC metadata via
`GoogleDiscoveryService`, never hardcoded. There are no refresh tokens — renewal is a fresh
authorization redirect. Clients still send `Authorization: Bearer <id_token>`, so validation is
identical whether the token came from this flow or a frontend.

`User.id` **is** the Google `sub` claim — there is no separate subject column, and Google issues no
`preferred_username`, so never assume that claim.

**Admission is invite-only and enforced at authentication, not authorization.**
`UserProvisioningService` throws `InvalidBearerTokenException` (→ 401, no `Authentication` built) when
`email_verified` is absent/false, when an unknown email is not in `allowed_email`, or when the user is
inactive. Do not weaken this into an authorities check: an authenticated-but-unauthorized caller still
reaches every `/api/me/**` endpoint, since those are deliberately permission-free — that exact
mistake let deactivated users keep access until Sprint 10. The allowlist governs _first admission
only_; `active` governs afterwards.

### Authorization: dynamic permissions, not roles

Two deliberately separate meanings of "admin":

- **Business authz** — `Role` →(M:M)→ `Permission`. `DynamicJwtAuthenticationConverter` →
  `DynamicAuthoritiesService` provisions the user on first sight and grants one `PERM_<code>`
  authority per permission, cached in Caffeine for 60s. Gate every endpoint with
  `@PreAuthorize("hasAuthority('PERM_X')")`. **Never** write a role-name check.
- **JHipster infrastructure** — the static `ROLE_ADMIN` string gates `/management/**` only, and is
  granted solely to users whose domain role is `SUPER_ADMIN`. JHipster's `Authority` /
  `jhi_user_authority` tables are gone.

New permissions are added to `src/main/resources/config/permissions.json` (idempotently upserted by
`RbacSeedService` at startup). Mutating a user's role or active status must `evict(...)` the
authorities cache so the change is immediate rather than TTL-delayed.

`/api/me/**` resources are authenticated-only with **no** permission gate, and ownership is enforced
by the repository query itself. Each such resource's Javadoc states this explicitly — keep that.

### Domain flow

```
WeeklyPrayerConfiguration(+Day)  ── versioned template; "current" = effective_to IS NULL
        │  (snapshotted at generation time — later config edits never alter past sessions)
        ▼
Roster ──(M)── PrayerSession ──(1..2)── PrayerAssignment   ← the Timefold @PlanningEntity
        │                                    user = @PlanningVariable, locked = @PlanningPin
        └──(M)── RosterGeneration            ← immutable audit row per solve attempt
```

`PrayerAssignment` rows are created **only when required**, so "no preacher on moderation-only days"
is structurally guaranteed, never a runtime constraint. A feasible solve (`hardScore == 0`)
auto-publishes; infeasible leaves the roster `DRAFT` (or `REQUIRES_RESCHEDULING`) with a per-constraint
violation breakdown on the `RosterGeneration` row. `RosterSolvingService` is the single shared
"solve then apply the outcome" path used by both generation and rescheduling.

In Constraint Streams, `forEach(PrayerAssignment.class)` **excludes unassigned entities** — a
`user != null` check there is unreachable, not merely redundant. Only `everyAssignmentMustBeFilled`
opts back in via `forEachIncludingUnassigned`.

Rescheduling is event-driven: availability/deactivation changes publish events consumed by
`ReschedulingDetectionListener` at `@TransactionalEventListener(AFTER_COMMIT)`; unaffected
assignments are `@PlanningPin`-locked so they structurally cannot move.

### Transactions — the recurring trap

Calling an `@Transactional` method from inside an `AFTER_COMMIT` callback **silently loses its
writes** (no exception). The codebase's two established fixes:

- an explicit `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW` (`ReschedulingDetectionService`,
  `UserProvisioningService`, `ReminderService`), or
- `@Async` **plus** `AFTER_COMMIT` so the work lands on a thread with no ambient transaction
  (`EmailNotificationListener`).

Never rely on annotation-based propagation resolution in that position.

### Notifications & scheduled jobs

`Notification` stores a `messageKey` + locale-independent JSON params, resolved to text only at
read/send time by `NotificationTextResolver` — never pre-render. All user-facing text lives in
`src/main/resources/i18n/messages_fr.properties` (default language is **French**) / `_en`; PDFs and
emails resolve the _recipient's_ `langKey`.

Three `@Scheduled` + `@SchedulerLock` jobs: `RollingRosterGenerationService` (monthly, keeps ~2 months
generated ahead), `ReminderService` (daily sweep), `EmailNotificationService.retryFailedEmails`.
ShedLock only prevents wasted duplicate work — **correctness** comes from the `ReminderSent`
`unique(assignment_id, reminder_configuration_id)` ledger and insert-or-skip. Scheduled jobs swallow
their expected failure cases (e.g. no config covers the window yet) rather than crashing the scheduler.

Their cron/interval overrides are read as plain `${...}` placeholders and are **not** modelled in
`ApplicationProperties`, which is `@ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)`.
Adding e.g. `application.reminders.cron` to a yml file will therefore fail context startup unless you
also add the field to `ApplicationProperties`.

## Conventions

- **Layering is enforced by ArchUnit** (`TechnicalStructureTest`): config → web → service → security →
  repository → domain, one direction only. A dependency that skips a layer fails the build.
- **DTOs are Java records with a static `from(entity)` factory.** MapStruct is on the classpath and
  the architecture doc mentions mappers, but no mapper exists — follow the records. Never return raw
  JPA entities.
- The actual package layout is JHipster's flat technical layering (`domain`/`repository`/`service`/
  `web/rest`), **not** the per-feature modular layout sketched in architecture doc §15.
- **Liquibase changelogs are hand-written** (`ddl-auto: none`), named
  `<timestamp>_added_entity_<Name>.xml`, and must be registered in `master.xml`. Postgres specifics
  matter: NULLs are distinct, so "at most one current version" needs a partial unique index
  (`UNIQUE ((true)) WHERE effective_to IS NULL`), not a plain unique on a nullable column.
- **N+1 is treated as a bug**: read paths use explicit join-fetch queries. Derived query names are
  not validated by Mockito-mocked repositories — prefer an explicit `@Query` where Spring Data's
  parser could split a property name (`daysBefore` → `days` + `Before` bit this codebase once).
- `README.md` is stock JHipster boilerplate. Its Keycloak / Okta / Auth0 sections describe a
  login flow this app does not implement — ignore them.

## Environment variables

`GOOGLE_CLIENT_ID` (audience validation), `GOOGLE_CLIENT_SECRET` and `GOOGLE_REDIRECT_URI` (required
for the code exchange; the redirect URI must exactly match one registered in the Google Cloud
console), `GOOGLE_ISSUER` (defaults to `https://accounts.google.com`), `INITIAL_SUPER_ADMIN_EMAIL` —
applied only on **first creation** of a user row, so a later manual demotion is never silently undone,
and treated as an implicit allowlist entry so the first sign-in works against an empty database.

## Definition of done

The `backend-engineering-standards` skill is the checklist run before any backend work is considered
complete (security pass, N+1/query pass, complexity, coverage, API docs). Invoke it rather than
re-deriving the list.
