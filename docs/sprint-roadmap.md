# Prayer Roster Backend — Sprint Roadmap

Tracks implementation against `docs/phase1-architecture.md`. Each sprint should land with: tests
(100% line+branch coverage on hand-authored business logic, JaCoCo-gated), updated OpenAPI docs
(springdoc, live at `/v3/api-docs`), a security pass (permission-based authz, no hardcoded role
checks), and a query-performance pass (no N+1 — verified via Hibernate statistics in tests).

- [x] **Sprint 0 — Bootstrap**: JHipster 8.11.0 backend-only monolith generated (Maven, PostgreSQL
      dev+prod, oauth2 auth type, fr/en). Local git repo initialized (JHipster's own generator does
      this). JaCoCo coverage gate, sprint roadmap, engineering-standards skill.
- [x] **Sprint 1 — Auth & Dynamic RBAC** (core landed; see note below): persisted `User`/`Role`/
      `Permission` entities + Liquibase (`app_user`/`app_role`/`permission`/`role_permission`,
      `id`=Google `sub` on `User`) + idempotent `permissions.json` loader (`RbacSeedService`).
      Stripped JHipster's session/oauth2Login/oauth2Client scaffolding (`OAuth2Configuration`,
      `OAuth2RefreshTokensWebFilter`, `CustomClaimConverter`, `LogoutResource`, the Keycloak Docker
      service) down to stateless Resource Server only. Dynamic `PERM_*`/`ROLE_ADMIN` authority
      resolution with a race-safe provisioning path (`UserProvisioningService`,
      `DynamicAuthoritiesService`, `DynamicJwtAuthenticationConverter`) and SUPER_ADMIN bootstrap via
      `INITIAL_SUPER_ADMIN_EMAIL`. Found and fixed two real bugs surfaced along the way in
      `SecurityUtils` (a `preferred_username` claim assumption Google never satisfies, and
      authorities being re-derived from JWT claims instead of what the converter actually granted).
      47 unit tests, 100% line+branch coverage on all new business logic (JaCoCo-verified locally).
      Live-verified end-to-end against a real Postgres (app boot, RBAC seeding, endpoint auth
      behavior) on two independent Docker runtimes (Docker Desktop and Colima). The automated
      Testcontainers IT suite itself remains blocked in this environment: Testcontainers' own
      pre-flight probe negotiates Docker API 1.32, rejected by any engine enforcing the newer >=1.40
      floor - confirmed this is a Testcontainers-internal limitation (not docker-java, not the
      engine) by swapping runtimes and forcing both libraries to their latest releases with no
      change in behavior. Committed as `4d85df4`.
- [x] **Sprint 2 — User Management & Capabilities**: `UserAvailability` entity + Liquibase
      (`user_availability`, FK cascade delete, `(user_id, start_date)` index), self-service CRUD at
      `/api/me/availability` (authenticated-only, ownership enforced by the repository query itself -
      no separate authorization check to get wrong), and admin user management at `/api/users`
      (`GET`/`GET {id}`/`PUT {id}`/`PUT {id}/status`/`PUT {id}/role`), all N+1-safe via join-fetch
      queries. Added a dedicated `USER_ROLE_ASSIGN` permission, deliberately excluded from `ADMIN`'s
      default set and kept `SUPER_ADMIN`-only (via "gets everything") - a plain `USER_UPDATE` gate
      on role assignment would have let any `ADMIN` promote themselves or anyone else to
      `SUPER_ADMIN`. Both mutation services evict `DynamicAuthoritiesService`'s cache on status/role
      change so it takes effect immediately, not after the TTL. Controllers tested via standalone
      MockMvc (no Spring context/DB needed), closing a real gap along the way: `AccountResource`,
      `AuthInfoResource`, and `SpringSecurityAuditorAware` had **zero** test coverage since Sprint 1 -
      the coverage gate had never actually caught this because of a separate bug (below). 51 new
      tests, 98/98 total passing, JaCoCo gate genuinely green (19 classes, 100%). Live-verified
      end-to-end (app boot, schema, auth-protection on every new route) against a real Postgres.
- [x] **Sprint 3 — Weekly Prayer Configuration**: `WeeklyPrayerConfiguration`/`Day` versioned
      template (no redundant `active` boolean - "current" is derived from `effective_to IS NULL`,
      the single source of truth). Found and fixed a real bug before it could bite: a plain unique
      index on `effective_to` would **not** have enforced "at most one current version" in Postgres
      - NULLs are distinct from each other there, so multiple NULL rows would've been allowed. Used
      a partial unique index on a constant expression (`UNIQUE ((true)) WHERE effective_to IS NULL`)
      instead, and proved it actually rejects a second current row via a live raw-SQL test, not just
      by eyeballing the DDL. `PUT /api/prayer-config/weekly` creates a new version and closes the
      previous one, except a second edit on the same calendar day, which amends that day's version
      in place instead of piling up same-day versions with an otherwise-invalid
      `effective_to < effective_from`. Also simplified away a genuinely unreachable branch found via
      the coverage gate itself: checking `Set<DayOfWeek>.size() == 7` already guarantees every day
      is present with no duplicates (`DayOfWeek` has exactly 7 values), so a separate `containsAll`
      check could never evaluate differently - deleted rather than tested around. 18 new tests
      (111/111 total), JaCoCo gate green (21 classes, 100%). Live-verified end-to-end.
- [x] **Sprint 4 — Roster & Session Generation**: `Roster`, `RosterGeneration` (immutable audit row
      per solve attempt - `triggeredAt`/`triggeredBy` deliberately reuse `createdDate`/`createdBy`
      rather than duplicating them), `PrayerSession`, `PrayerAssignment` (rows only created when
      required, so "no preacher on moderation-only days" is structurally guaranteed, not a runtime
      check). `RosterGenerationService` resolves every date's applicable config version and day
      setting entirely in memory against one query for the whole version history - O(1) queries
      regardless of period length, never one query per date. Fully validates the whole period
      up front before writing anything, so a failure never leaves partial/orphaned rows. `POST
      /api/rosters/generate`, `GET /api/rosters[/{id}]`, `GET /api/prayer-sessions[/{id}]`, all
      N+1-safe. 27 new tests (134/134 total), JaCoCo gate green (27 classes, 100%). Live-verified
      end-to-end - including proving the `ux_prayer_session_date` and
      `ux_prayer_assignment_session_id_role` constraints actually reject violations, not just that
      they exist in the DDL, and exercising the full generated shape (session → assignment count
      per day) via raw SQL against a real Postgres.
- [x] **Sprint 5 — Timefold Solver Integration**: `PrayerAssignment` is now the Timefold
      `@PlanningEntity` (`user` as `@PlanningVariable`, `locked` as `@PlanningPin` - inert until
      Sprint 6's reschedule pinning), `RosterSolution` (`@PlanningSolution`), `RosterConstraintProvider`
      (7 hard + 4 soft constraints), and `SolverService` (thin `SolverManager` wrapper, blocking
      `solve()` that's safe inside a transactional method since Timefold runs on its own thread pool,
      plus `explainHardConstraintViolations()` for infeasible diagnostics). `RosterGenerationService`
      now solves after structural generation: feasible (`hardScore == 0`) auto-publishes the roster
      and fills every assignment; infeasible leaves the roster `DRAFT` with a per-constraint violation
      breakdown on the `RosterGeneration` row, matching the roster-lifecycle decision (no manual
      review step for the happy path). Zero eligible users is special-cased before ever calling the
      solver - Timefold refuses to solve a genuinely empty value range outright, and there's nothing
      to gain from trying anyway.
      Found and fixed two real bugs along the way: (1) **Sprint 4's `PrayerSession.assignments`
      had no cascade and `RosterGenerationService` never explicitly saved `PrayerAssignment` rows** -
      structurally correct in the mock-based unit tests (which only assert what repository methods
      were called), but would have silently generated sessions with **no assignment rows at all**
      against a real database; fixed by explicitly saving each assignment and maintaining both sides
      of the bidirectional association. (2) `forEach(PrayerAssignment.class)` in Constraint Streams
      silently **excludes entities whose planning variable is still null** - discovered when a
      `ConstraintVerifier` test kept reporting 0 matches for facts that should have hit the filter.
      This makes an explicit `user != null` check dead code (unreachable, not just redundant) on
      every constraint except `everyAssignmentMustBeFilled`, which deliberately opts back in via
      `forEachIncludingUnassigned`; simplified all ten other constraints accordingly rather than
      leaving an unreachable branch in place.
      28 new tests (162/162 total): `RosterConstraintProviderTest` (`ConstraintVerifier`, one
      constraint at a time, `given()`/`penalizesBy()`), `SolverServiceTest` (real `SolverManager`
      end-to-end solves - feasible, infeasible, interrupted-thread handling, and a genuine
      solve-time exception via a throwaway `ConstraintProvider` test double), `RosterSolutionTest`,
      and updated `RosterGenerationServiceTest`. JaCoCo gate green (30 classes, 100%). Live-verified
      end-to-end against a real Postgres: a feasible solve genuinely filled every assignment by
      capability (moderator-only user got every `MODERATOR` slot, preacher-only user got the
      `PREACHER` slot) and the roster auto-published; deactivating the only preacher-capable user and
      regenerating produced `RosterGeneration.status = INFEASIBLE` with
      `errorMessage = "moderatorAndPreacherMustDiffer: 1 violation(s); userMustHavePreachingCapability:
      1 violation(s)"` and the roster correctly stayed `DRAFT`. Also hit and worked around a local
      environment quirk unrelated to the app itself: a native macOS Postgres was already listening on
      127.0.0.1:5432, shadowing the Docker container's port-forward - moved verification to port 5433.
- [x] **Sprint 6 — Rescheduling**: extracted `RosterSolvingService` (the shared "solve then apply the
      outcome" logic from Sprint 5, parameterized by which `RosterStatus` to fall back to on
      infeasible - `DRAFT` for initial generation, `REQUIRES_RESCHEDULING` for an already-published
      roster) so generation and rescheduling never duplicate that logic. Detection is event-driven:
      `UserAvailabilityService` (create/update, not cancel - becoming *more* available never
      invalidates an existing assignment) and `UserManagementService` (deactivation only, not
      reactivation) publish plain `ApplicationEventPublisher` events, picked up by
      `ReschedulingDetectionListener` via `@TransactionalEventListener(phase = AFTER_COMMIT)` -
      deliberately waiting for the triggering change to actually commit before detection ever runs,
      since detection needs to see it as durable fact. `ReschedulingDetectionService` finds every
      affected assignment on an already-`PUBLISHED` roster (join-fetched with session+roster in one
      query, never N+1), flags the session and rosters, then re-solves each affected roster with
      every assignment on an unflagged session pinned via Timefold's `@PlanningPin`
      (`PrayerAssignment.locked`) - a structural guarantee that an unaffected, already-published
      assignment cannot move, not a soft preference. `POST /api/rosters/{id}/reschedule` (gated by
      the already-seeded `PERM_ROSTER_RESCHEDULE` permission) reuses the exact same
      `ReschedulingService.reschedule()` entry point to force a retry or re-attempt after a failed
      auto-reschedule, using whatever sessions are currently flagged rather than taking its own scope.
      Weekly-configuration changes are deliberately *not* a detection trigger: session requirements
      are snapshotted at generation time and never retroactively altered by a later config edit (see
      Sprint 4), so there's structurally nothing for a config change to invalidate.
      Found and fixed one real, serious bug via live testing - unit tests could not have caught
      this, since Mockito never exercises real Spring transaction management: calling an
      `@Transactional` service method from inside a `@TransactionalEventListener(phase =
      AFTER_COMMIT)` callback relies on Spring correctly resolving "no transaction is active right
      now" at that exact moment to start a fresh one. In practice that resolution was unreliable
      immediately after the triggering transaction's own commit - the reschedule *appeared* to
      succeed (a real Timefold solve ran, a sequence value was consumed for the new
      `RosterGeneration` row, the in-memory `RosterDTO` reflected the update) but the write was never
      durably committed, with no exception anywhere to catch or log. Fixed by giving
      `ReschedulingDetectionService` its own explicit `TransactionTemplate` with
      `PROPAGATION_REQUIRES_NEW` (the same established pattern as `UserProvisioningService`, chosen
      deliberately over relying on annotation-based propagation resolution) - verified the fix
      actually works by re-running the same live scenario and confirming the generation row was
      genuinely there afterward, not just that no exception was thrown.
      21 new tests (183/183 total): `RosterSolvingServiceTest`, `ReschedulingServiceTest`
      (pinning, the "nothing flagged" guard, infeasible-leaves-it-flagged), `ReschedulingDetectionServiceTest`
      (date clamping to today, distinct-roster dedup, never-propagates-a-failure), and
      `ReschedulingDetectionListenerTest`, plus event-publishing coverage added to
      `UserAvailabilityServiceTest`/`UserManagementServiceTest` and a new endpoint test in
      `RosterResourceTest`. JaCoCo gate green (36 classes, 100%). Live-verified end-to-end against a
      real Postgres, including the bug above and its fix: submitting availability for an
      already-assigned user on a `PUBLISHED` roster triggered detection, a real re-solve, and a
      republish with the conflicting session correctly reassigned; a second, immediately-following
      availability submission that left *no* remaining moderator-capable candidate for that day
      correctly came back `INFEASIBLE` and left the roster `REQUIRES_RESCHEDULING` rather than
      applying a broken partial solution; every unaffected session's assignment (across *two*
      successive reschedule attempts) was proven byte-for-byte unchanged, confirming pinning holds.
- [x] **Sprint 7 — Notifications & Reminders**: `Notification` (in-app record and email side-effect
      tracked on the same row - `messageKey` + a locale-independent JSON `params` map, resolved to
      final text only at read/send time via `NotificationTextResolver`, never pre-rendered, so a
      `User.langKey` change is always reflected correctly), `NotificationPreference` (email opt-out),
      `ReminderConfiguration`/`ReminderSent` (admin-managed offset list, seeded `[7, 1]`, with the
      ledger's `unique(assignment_id, reminder_configuration_id)` constraint as the true idempotency
      guarantee). `NotificationService` publishes a `NotificationCreatedEvent`; `EmailNotificationListener`
      combines `@Async` **and** `@TransactionalEventListener(phase = AFTER_COMMIT)` deliberately - the
      `@Async` hop dispatches to a genuinely new thread with no ambient transaction, sidestepping
      Sprint 6's exact pitfall (a synchronous `@Transactional` call from an `AFTER_COMMIT` callback
      silently losing its writes) by construction rather than by care. `EmailStatus` gained a fourth
      `SKIPPED` value beyond the architecture doc's `PENDING|SENT|FAILED`, used when the recipient has
      opted out - needed to avoid either misusing `SENT` for something never sent, or leaving the row
      stuck at `PENDING` forever inviting endless useless retries. `ReminderService`'s sweep reuses
      `UserProvisioningService`'s established idempotent-insert pattern: `PROPAGATION_REQUIRES_NEW` +
      `saveAndFlush()` per assignment to force the unique-constraint violation to surface *before* any
      notification gets created from it, catching `DataIntegrityViolationException` as a silent
      "already sent" no-op. ShedLock (`shedlock-spring`/`-provider-jdbc-template`) guards both the
      reminder sweep and the email-retry sweep against wasted duplicate work if the app ever runs more
      than one instance - explicitly *not* the correctness guarantee, that's the ledger constraint.
      `RosterSolvingService` now diffs each assignment's user before/after a feasible solve and
      notifies the newly-assigned user and (if different) whoever lost the slot; simplified away a
      second dead `if (newUser != null)` guard once the coverage gate flagged it as unreachable - a
      feasible solve (`hardScore == 0`) mathematically guarantees every assignment ends up filled, via
      the same `everyAssignmentMustBeFilled` hard constraint from Sprint 5.
      Found and fixed one genuine test-authoring bug and one genuine production bug. The test bug: two
      new `RosterSolvingServiceTest` cases failed with "zero interactions" against code that looked
      correct, traced to the test's own solve-mock mutating the same `PrayerAssignment` objects
      in place and returning them, instead of building real clones the way Timefold's actual
      `getFinalBestSolution()` does - the pre-solve "previous user" read was silently seeing the
      post-solve value, making every case look like "no change." Fixed with a `solvedClone()` helper
      documented for future contributors. The production bug - found only by live verification, since
      Mockito-mocked repositories never validate derived-query syntax: `ReminderConfigurationRepository
      .existsByDaysBefore(Integer)` failed at real `JpaRepository` initialization with "No property
      'days' found for type 'ReminderConfiguration'" - Spring Data's parser split "DaysBefore" into
      property `days` + the reserved `Before` (date-comparison) keyword, since a whole-word
      `daysBefore` property isn't distinguishable from that split. Fixed with an explicit `@Query`.
      54 new tests (237/237 total), JaCoCo gate green (50 classes, 100%). Live-verified end-to-end
      by booting the packaged jar (not a JUnit IT - see environment note below) against a real,
      separately-run Postgres (port 5433) and a throwaway MailHog SMTP catcher: a feasible solve
      genuinely created a `Notification` row with the correct `messageKey`/params and the async
      listener genuinely delivered a real email to MailHog after the publishing transaction committed
      (`emailStatus` correctly flipped to `SENT`); the reminder sweep, run twice, produced exactly one
      `ReminderSent` ledger row and one notification (the second run was actually rejected by
      ShedLock's `lockAtLeastFor` before even reaching the ledger-constraint layer - real evidence
      the lock itself isn't a no-op, on top of the ledger-constraint path already covered by unit
      tests). The `ReminderConfigurationResource` permission gating was verified via the passing
      MockMvc suite rather than a live authenticated HTTP round-trip - forging a real signed Google ID
      token outside an actual browser OAuth flow isn't practical, and the underlying `PERM_*` JWT
      pipeline itself is general infrastructure already live-verified in Sprint 1, unchanged here.
- [x] **Sprint 8 — PDF Export & Calendar**: `PdfRenderingService` (Thymeleaf → openhtmltopdf,
      `openhtmltopdf-core`/`-pdfbox`, Apache-2.0) is a thin, generic wrapper reused by both new
      endpoints, matching the architecture doc's "reuse the same Thymeleaf skill already needed for
      email templates" call. `GET /api/rosters/{id}/pdf` (`PERM_ROSTER_VIEW`, same authority as the
      existing roster read endpoint - no new permission needed) renders a week-grouped table (weeks
      keyed by their Monday, built in `RosterPdfService` rather than in the template, so the grouping
      logic itself is directly unit-testable); an unfilled slot prints a localized "vacant"
      placeholder instead of a blank cell, and a moderation-only day's preacher column is deliberately
      left empty rather than showing "vacant" - a day that never required a preacher isn't missing
      one. `GET /api/me/prayer-assignments` (JSON) and `GET /api/me/prayer-assignments/pdf` both read
      through one new `PrayerAssignmentService.findOwnUpcoming` (self-service, authenticated-only, no
      permission gate - same reasoning as every other `/api/me/**` resource) so the "upcoming" window
      (today .. +6 months, generous relative to the ~2-month rolling generation horizon) is defined in
      exactly one place. Both PDF endpoints resolve the *requesting* user's own `langKey` to a
      `Locale` (falling back to French if somehow unauthenticated or not found) via the same pattern
      Sprint 7 established for notifications - a French-speaking admin gets a French roster PDF, an
      English-speaking user gets their English calendar.
      Found and fixed one genuine bug via a failing test, before it ever reached a real database: a
      classic `Stream.findFirst()` trap in `RosterPdfService.findAssignedUser` - `.map(PrayerAssignment::getUser)`
      before `.findFirst()` throws `NullPointerException` the moment an unfilled assignment's
      `getUser()` returns `null`, because `findFirst()`/`findAny()` wrap their result via
      `Optional.of` internally, not `Optional.ofNullable` - a stream can carry `null` elements
      through `map`, it just can't survive being terminally collected into an `Optional` afterward.
      Fixed by finding the (never-null) `PrayerAssignment` first, then `.map`-ping to its
      (possibly-null) user only after the `Optional` already exists.
      21 new tests (258/258 total), JaCoCo gate green (58 classes, 100%). Live-verified end-to-end by
      booting the packaged jar (not a JUnit IT - see Sprint 7's environment note) against a real,
      separately-run Postgres: a roster PDF genuinely rendered as valid, readable French text
      (`pdftotext`-extracted) with correct week headers, a real assigned name for a filled slot, and
      "À pourvoir" for an unfilled one; a personal calendar PDF for an English-locale user genuinely
      rendered "September 22, 2026" / "preacher" in English; the JSON `/api/me/prayer-assignments`
      listing returned exactly the one upcoming published assignment expected.
- [x] **Sprint 9 — Cron Automation & Hardening**: `RollingRosterGenerationService` (`@Scheduled` +
      `@SchedulerLock`, same pattern as Sprint 7's reminder/email sweeps) keeps generation rolling
      ~2 months ahead of today, on top of admin-triggered generation for any custom range - per the
      architecture doc's Horizon decision. It walks forward from `RosterRepository`'s new
      `findTopByOrderByPeriodToDesc()` (added this sprint - none of the roster-window bookkeeping
      needed it before), deliberately unfiltered by `RosterStatus` since `RosterGenerationService`'s
      own overlap gate (`PrayerSessionRepository.existsByDateBetween`) is itself status-agnostic - an
      `ARCHIVED` roster's sessions still count as "covered." `RosterGenerationService.generate(...)`
      gained a second, trigger-parameterized overload so the job's `RosterGeneration` rows are
      correctly tagged `SCHEDULED_CRON` (an enum value that had existed unused since Sprint 4,
      evidently added in anticipation of exactly this job) instead of `MANUAL`; the existing
      single-arg `generate(request)` now just delegates with `MANUAL`, so every prior caller is
      unaffected. A `BadRequestAlertException` from `generate(...)` (most likely: no weekly
      configuration covers part of the window yet) is caught and logged rather than left to crash the
      scheduler - matching `ReschedulingDetectionService`'s established "never propagate a scheduled
      job's expected-failure case" precedent from Sprint 6, and next month's run naturally retries the
      same gap.
      The security/perf/N+1 audit turned up one confirmed-safe finding worth documenting rather than
      changing: `UserRepository.findAllEligibleActive()` returns users without join-fetching the
      `role` association, and is iterated by the Timefold solve path - but every constraint that reads
      a user only touches `canModerate`/`canPreach` (plain columns, not the lazy `role` relation), so
      there's no actual N+1 here despite the missing fetch join. Also closed one real documentation
      gap: `MeNotificationPreferenceResource` was the one `/api/me/**` self-service resource whose
      Javadoc didn't state "deliberately not permission-gated" like its three siblings, even though it
      followed the identical pattern - now consistent, so a future audit doesn't have to re-derive
      that it's intentional.
      12 new tests (264/264 total), JaCoCo gate green (59 classes, 100%). Live-verified end-to-end by
      booting the packaged jar against a real, separately-run Postgres: with no roster yet generated,
      one run of the job correctly produced a roster covering today through exactly today+2 months,
      tagged `SCHEDULED_CRON`; a second immediate run was a genuine no-op (identical roster count),
      confirming the "walk forward from the furthest existing `periodTo`" logic doesn't double-generate.
      This closes out all 9 sprints in the roadmap - see docs/phase1-architecture.md's "Next Steps" for
      the closing summary.

## Local environment notes

- **Docker runtime is Colima**, not Docker Desktop (`docker context use colima`, or just leave it -
  it's the active context). Docker Desktop was fully quit to free memory; both running at once
  caused an OOM kill mid-build once. `~/.testcontainers.properties` points at Colima's socket. This
  did **not** fix the Testcontainers IT-suite blocker above - that's a separate, deeper issue inside
  Testcontainers itself, confirmed identical on both runtimes.
- **`mvn verify` needs `-Dmaven.test.failure.ignore=true`** to get past the known-blocked IT suite
  and actually reach the `coverage-check` execution. Plain `mvn test` (no ITs) is enough for fast
  local iteration and does reflect real coverage.
- **JaCoCo gotcha, already fixed but worth knowing**: a `<rule><excludes>` list under an
  `element=BUNDLE` rule does *not* filter the bundle-wide ratio the coverage gate checks - it's
  silently ignored for that element type. The real fix is the plugin-level `<configuration><excludes>`
  block (class-file globs, `/`-delimited) in the same `jacoco-maven-plugin` block, which filters at
  analysis time and does work. Keep both lists in sync when adding new excluded packages.
- **Testcontainers now works locally** (Sprint 7): bumping `-Dtestcontainers.version=1.21.4` past the
  pinned `1.20.6` fixes the Docker API negotiation failure noted above, and
  `TESTCONTAINERS_RYUK_DISABLED=true` works around Colima being unable to bind-mount its own
  `docker.sock` into the reaper container. Neither is wired into the POM/CI - this is a manual local
  recipe, not yet a supported `mvn verify` path.
- **A genuinely full `@SpringBootTest` still can't safely boot in this codebase, for an unrelated
  reason**: Timefold's Spring Boot starter performs an eager, unconditional classpath scan for every
  `ConstraintProvider` implementation as part of its own startup diagnostics, and refuses to boot if
  it finds more than one - even with an explicit `src/main/resources/solverConfig.xml` pinning
  `RosterConstraintProvider` by name. `SolverServiceTest`'s `ThrowingConstraintProvider` test double
  (a second, deliberately-broken implementation, needed to exercise a real solve-time failure) makes
  this unavoidable the moment `src/test/java` and a real `SolverManager` bean share a classpath. Live
  verification therefore boots the packaged jar (production classpath only) directly, the same way
  every prior sprint's live verification did - not a `@SpringBootTest`-based IT class.

## Definition of Done (every sprint)

See the `backend-engineering-standards` skill for the full checklist run before a sprint is
considered complete.
