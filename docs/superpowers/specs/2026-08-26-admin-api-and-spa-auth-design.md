# Sprint 11 — Admin API Surface & SPA Auth Landing

Status: proposed design, not yet implemented
Depends on: `2026-08-25-backend-google-auth-design.md` (merged as `dfe9b0d`)

## Problem

A frontend is about to be built, and two classes of gap block it.

**It cannot log in at all.** `GET /api/auth/google/callback` returns the ID token as a JSON body.
That works for curl, but a browser *navigates* to that URL: the SPA is gone, the user sees raw JSON,
and the token never re-enters JavaScript. The previous spec deferred "a browser-app landing or
handoff code" explicitly, on the grounds that no frontend existed. One now does.

**Half the permission catalogue has no endpoint behind it.** Thirteen of the twenty-six seeded
permissions are referenced nowhere in `web/rest`:

```
ROLE_VIEW/CREATE/UPDATE/DELETE     PERMISSION_VIEW/CREATE/UPDATE/DELETE
ROSTER_REGENERATE  ROSTER_PUBLISH  AVAILABILITY_VIEW  AVAILABILITY_MANAGE  NOTIFICATION_VIEW
```

The RBAC model anticipated an admin surface that was never built. The domain layer is largely ready
— `RosterGeneration` already stores every solver metric, `UserAvailabilityRepository` already has
`findActiveOverlapping`, `Role` already owns its `Set<Permission>` — so most of this is a resource
and a DTO, not new modelling.

## Scope

In: the SPA auth landing, a roles/permissions read-write API, roster generation history, admin
access to member availability, and an availability conflict preview.

Out, with reasons — these are decisions, not omissions:

- **`ROSTER_PUBLISH`.** Publishing is automatic on a feasible solve (architecture §11, deliberate).
  An explicit publish action could only ever publish a roster that *failed* to solve. Building it
  would add a footgun; the permission should be removed from the catalogue instead.
- **`PERMISSION_CREATE/UPDATE/DELETE`.** The catalogue is code-defined in `permissions.json` and
  re-seeded at every boot, so write endpoints would fight the seeder. `GET` only.
- **`NOTIFICATION_VIEW`.** `/api/me/notifications` is self-service and correctly ungated. An admin
  view of other people's notifications has no product need; remove from the catalogue.
- **Session time-of-day.** `PrayerSession` holds only a `date`. Adding a time touches the entity, a
  changelog, the weekly configuration, and both PDF templates — real scope for one line of UI text.
- **`ROSTER_REGENERATE`.** Deferred, not rejected: `POST /api/rosters/generate` refuses overlapping
  periods, so regeneration means deciding what happens to existing assignments. That deserves its
  own design pass rather than being smuggled in here.

## B1 — SPA auth landing (blocker; build first)

`application.frontend.base-url` is added to `ApplicationProperties` (which binds with
`ignoreUnknownFields = false`, so the field is mandatory, not optional).

- **Configured** — the callback issues `302` to `${frontend.base-url}/auth/callback?handoff=<opaque>`
  and the SPA exchanges it: `POST /api/auth/exchange {handoff}` → `{idToken, expiresIn}`.
- **Not configured** — the callback keeps returning JSON exactly as today.

Both branches are kept deliberately. The JSON branch is how this project live-verifies (a full
`@SpringBootTest` cannot boot here, so verification drives the packaged jar by hand), and silently
removing it would break the only manual path that exists.

The handoff is opaque, single-use, and short-lived (60 seconds — it is redeemed within milliseconds
of the redirect). It reuses the `AuthorizationRequestStore` pattern: a second Caffeine cache,
removed atomically on consume. **No token ever appears in a URL**, so none reaches browser history,
`Referer` headers, or access logs — which is the entire reason for the indirection.

`/api/auth/exchange` is `permitAll`. CORS already allows `localhost:3000` and `:5173` in the dev
profile.

## B2 — Roles and permissions API

| Endpoint | Permission |
|---|---|
| `GET /api/roles` | `ROLE_VIEW` |
| `POST /api/roles` | `ROLE_CREATE` |
| `PUT /api/roles/{id}` | `ROLE_UPDATE` |
| `DELETE /api/roles/{id}` | `ROLE_DELETE` |
| `GET /api/permissions` | `PERMISSION_VIEW` |

`RoleDTO(id, name, description, permissionCodes, userCount)` — `userCount` so the UI can warn before
a destructive edit.

Guards, each of which prevents a way to lock everyone out:

- The three baseline roles (`SUPER_ADMIN`, `ADMIN`, `USER`) cannot be renamed or deleted; their
  permission sets remain editable.
- A role held by at least one user cannot be deleted.
- `SUPER_ADMIN` cannot have permissions removed — it is the recovery path, and
  `DynamicAuthoritiesService` grants the static `ROLE_ADMIN` (the actuator gate) solely from it.

**Cache invalidation is the subtle part.** Editing a role changes the authorities of every user
holding it, but `DynamicAuthoritiesService.evict(userId)` is per-user. Without an `evictAll()`, a
permission change appears to do nothing for up to 60 seconds — the same class of bug as Sprint 2's,
where role mutation had to evict explicitly. `evictAll()` is added and called on every role write.

## B3 — Roster generation history

`GET /api/rosters/{id}/generations` (`ROSTER_VIEW`) →
`RosterGenerationDTO(id, trigger, status, planningFrom, planningTo, hardScore, softScore, feasible,
solverDurationMs, rescheduleReason, errorMessage, createdDate, createdBy)`.

Promised in architecture §14 and never built. `RosterGenerationRepository` is currently an empty
interface and needs one derived query ordered by `createdDate` descending.

## B4 — Admin availability

| Endpoint | Permission |
|---|---|
| `GET /api/users/{id}/availability` | `AVAILABILITY_VIEW` |
| `POST /api/users/{id}/availability` | `AVAILABILITY_MANAGE` |
| `DELETE /api/users/{id}/availability/{availabilityId}` | `AVAILABILITY_MANAGE` |

An admin recording an absence on a member's behalf must publish the same
`UserAvailabilityChangedEvent` the self-service path publishes, or rescheduling will not trigger and
the roster silently keeps an impossible assignment.

## B5 — Availability conflict preview

`GET /api/me/availability/conflicts?from=&to=` (authenticated) →
`ConflictingAssignmentDTO(date, role)[]`.

Read-only, mutating nothing. Without it the UI cannot warn *before* submission — it can only react
after the backend has already flagged sessions and started rescheduling, which is precisely the
surprise §18 of the frontend brief is trying to prevent.

## Testing

Unit tests in the established shape (Mockito, standalone MockMvc, no Spring context), 100% line and
branch on new `service` and `web/rest` classes or `verify` fails.

Behaviours that must be tested because they are where this breaks:

- handoff single-use, expiry, and unknown-handoff rejection
- the callback's two branches (redirect when configured, JSON when not)
- every role guard above, and that `evictAll()` is called on role writes
- admin availability publishing the rescheduling event

Live verification drives the packaged jar against a real Postgres, as every sprint has: complete a
real Google sign-in through the redirect branch and confirm the SPA-style exchange returns a working
token; edit a role's permissions and confirm the change takes effect on the *next request* rather
than after 60 seconds.

## Migration note

`permissions.json` loses `ROSTER_PUBLISH`, `NOTIFICATION_VIEW`, and the three `PERMISSION_*` write
codes. `RbacSeedService` upserts by code and never deletes, so removing them from the file leaves
orphaned rows in existing databases. A Liquibase changelog deletes them, along with any
`role_permission` rows referencing them.
