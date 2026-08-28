# Async Roster Generation, Rescheduling & Email

Status: proposed design, not yet implemented

## Problem

Three related pain points, all discovered live-testing the phase 5 admin screens:

**Generation and rescheduling block the HTTP request on the solve.** `RosterGenerationService.generate()`
and `ReschedulingService.reschedule()` are `@Transactional` methods that call
`RosterSolvingService.solveAndApply(...)` inline and don't return until Timefold finishes — seconds today,
longer as the community grows. `RosterGenerationStatus.RUNNING` already exists as an enum value, but because
the whole solve happens inside one transaction, no other request can ever observe a row genuinely `RUNNING`:
the transaction only commits once the terminal status (`COMPLETED`/`INFEASIBLE`) is already written. The
value is currently dead code.

**One assignment = one email.** `RosterSolvingService` calls `notificationService.notifyAssignmentPublished(managed)`
once per `PrayerAssignment` whose user changed. Generating a month where one person moderates four Mondays
and preaches twice fires six separate notifications — six separate emails — for a single roster. Confirmed
live: a November generation produced 20+ individual `assignmentPublished` rows in one run.

**Roster mutations don't refresh the notifications the admin might be looking at.** `useGenerateRoster` and
`useRescheduleRoster` (`frontend/src/features/admin/api.ts`) invalidate `["admin","rosters"]` and
`me/assignments` on success, but never `me/notifications` — so an admin who is also a roster participant,
and who already has the Notifications screen cached, sees nothing update after generating a roster that
just assigned them something. The notifications were created and emailed; the screen just never re-fetched.

**Invitation emails also block the request.** `AllowedEmailService.invite()`/`resend()` call
`MailService.sendEmail(...)` synchronously and inline — the one email-sending path in this codebase that
isn't already decoupled from the request thread (see Existing pattern, below).

## Existing pattern this design reuses

`NotificationService.create()` saves a `Notification` row, then does nothing else but
`eventPublisher.publishEvent(new NotificationCreatedEvent(saved.getId()))`. A separate listener:

```java
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onNotificationCreated(NotificationCreatedEvent event) {
    emailNotificationService.sendForNotification(event.notificationId());
}
```

picks it up after the creating transaction commits, on a thread from the `taskExecutor` bean
(`AsyncConfiguration`, real `ThreadPoolTaskExecutor` in every profile except test, where
`AsyncSyncConfiguration` swaps in a synchronous executor so tests stay deterministic). This is a
battle-tested, already-in-production pattern — the design below is the same shape applied to solving and
to invitation email, not a new mechanism.

## Scope

In: async generation, async rescheduling, consolidated per-run assignment notifications, async invitation
email, the missing `me/notifications` cache invalidation, frontend polling for the roster detail page while
a generation is running.

Out, with reasons:

- **Server-Sent Events / WebSockets.** A solve takes seconds to at most low minutes; polling the existing
  `GET /api/rosters/{id}/generations` endpoint every ~2s while `RUNNING` is imperceptibly different from a
  push and needs zero new backend infrastructure (no broker exists in this app today — confirmed, `pom.xml`
  has no amqp/kafka/jms dependency).
- **Live polling on the Plannings *list* screen.** `RosterDTO` doesn't carry generation status, only the
  roster's own `status`, and `DRAFT` is also the terminal state for an infeasible solve — the list can't
  distinguish "still running" from "finished infeasible" without a second query per row. The admin lands on
  the roster *detail* page immediately after starting a generation (unchanged from today), and that's where
  `GenerationHistory` already lives; that's where polling belongs. Revisiting the list later already
  refreshes on refetch.
- **Consolidating `notifyAssignmentRemoved`.** In scope for the same reason as published (a reschedule can
  reassign several sessions away from one person at once), but see the section below — it reuses the same
  batch primitive, so it isn't separate work.

## 1. Async generation and rescheduling

`PrayerAssignment` already carries a `generation` FK — every assignment created or re-touched by a run is
tagged with that run's `RosterGeneration` (`RosterGenerationService.createSession`,
`ReschedulingService.reschedule` both call `assignment.setGeneration(generation)`). That FK is the hook that
lets the solve step become fully self-contained and re-fetchable by id, rather than needing the caller's
in-memory entities.

**Split each caller into a fast synchronous phase and a deferred async phase:**

`RosterGenerationService.generate()` keeps everything it does today up through creating the roster, its
sessions, and their (unsolved, unassigned) `PrayerAssignment` rows tagged with a `RUNNING` generation — all
of that is plain, fast DB writes, no solving. Instead of calling `solveAndApply(...)` inline, it publishes
`RosterGenerationRequestedEvent(generation.getId())` and returns `RosterDTO.from(roster)` immediately
(`roster.status` is still `DRAFT`, honestly reflecting "not solved yet").

`ReschedulingService.reschedule()` keeps its validation, pin-flagging (`assignment.setLocked(...)`), and
re-tagging of affected assignments to the new generation — same reasoning, all fast writes — then publishes
the same event type and returns immediately.

**`RosterSolvingService.solveAndApply` changes signature from taking loaded entities to taking an id:**

```java
public boolean solveAndApply(Long generationId) {
    RosterGeneration generation = rosterGenerationRepository.findById(generationId).orElseThrow(...);
    Roster roster = generation.getRoster();
    List<PrayerAssignment> assignments = prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(generationId);
    RosterStatus statusOnInfeasible = generation.getTrigger() == RosterGenerationTrigger.RESCHEDULE
        ? RosterStatus.REQUIRES_RESCHEDULING
        : RosterStatus.DRAFT;
    // ... existing body, unchanged, using generation.getPlanningFrom()/getPlanningTo() for the
    // availability window instead of separately-passed from/to
}
```

This needs one new repository method, `PrayerAssignmentRepository.findByGenerationIdWithSessionAndUser`,
join-fetching `session` (always present) and **left**-join-fetching `user` — unlike the two existing queries
in that repository, `user` is legitimately null here for a brand-new generation's assignments (nothing has
been solved yet), and an inner join-fetch would silently return zero rows for exactly the case this method
exists to serve. Both fetches stay one query, no N+1 risk. The reschedule-only step of clearing `session.requiresRescheduling` on success moves inside
`solveAndApply` too (reachable via `assignment.getSession()` on the re-fetched list), gated on
`generation.getTrigger() == RESCHEDULE`.

**A new listener drives the solve:**

```java
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onRosterGenerationRequested(RosterGenerationRequestedEvent event) {
    rosterSolvingService.solveAndApply(event.generationId());
}
```

Same shape as `EmailNotificationListener`, same executor, same test-profile determinism via
`AsyncSyncConfiguration`. `solveAndApply` stays `@Transactional` itself (a fresh transaction, since it now
runs after the original one committed and on a different thread).

**API contract:** `POST /api/rosters/generate` and `POST /api/rosters/{id}/reschedule` keep returning
`RosterDTO` — the shape doesn't change, only what it represents (the just-created, not-yet-solved roster,
rather than the finished one). No new endpoint: `GET /api/rosters/{id}/generations` already returns exactly
what the frontend needs to watch (`status`, `feasible`, `hardScore`, `errorMessage`) per generation.

## 2. Frontend: close immediately, poll while running

The generate dialog and the reschedule dialog currently disable Escape/backdrop-click and show a spinner
until the mutation resolves — that resolution is now near-instant (it's just the fast synchronous phase), so
the dialog closes right away. Copy changes from "en cours" framing to "started" framing:
`t.rosters.generated`/`rescheduled` become e.g. *"Génération lancée"* / *"Replanification lancée"* rather
than implying completion.

On the roster detail page, `useRosterGenerations(id)` gains polling while the latest generation is
`RUNNING`, using TanStack Query's function form of `refetchInterval` so it stops itself once a generation
reaches a terminal status:

```ts
refetchInterval: (query) => {
  const latest = query.state.data?.[0]
  return latest?.status === "RUNNING" ? 2000 : false
}
```

When a generation flips out of `RUNNING`, also invalidate `adminKeys.roster(id)` so the page's status pill
(`current.status`) and the sessions table pick up the newly-published assignments without a manual reload.

## 3. Consolidated assignment notifications

`RosterSolvingService`'s per-assignment loop restructures to group by affected user first, then notify once
per user per run:

```java
Map<User, List<PrayerAssignment>> newlyPublished = new LinkedHashMap<>();
Map<User, List<PrayerAssignment>> newlyRemoved = new LinkedHashMap<>();
// ... same change-detection as today, but append to the lists above instead of notifying inline
newlyRemoved.forEach(notificationService::notifyAssignmentsRemoved);
newlyPublished.forEach(notificationService::notifyAssignmentsPublished);
```

`NotificationService` gains `notifyAssignmentsPublished(User, List<PrayerAssignment>)` and
`notifyAssignmentsRemoved(User, List<PrayerAssignment>)`, each creating **one** `Notification` row per call.
`Notification.params` for these two message keys stores a JSON *array* of `{date, role}` objects (today's
singular keys store a flat `{date, role}` object) — `NotificationTextResolver.resolveBody` branches on
whether the message key is one of the two plural ones, parses `List<Map<String,String>>` instead of
`Map<String,String>`, formats each item with the exact same per-item date/role localization already used
today, joins the lines, and substitutes that joined block as the single `{0}` in a new bundle template:

```properties
notification.assignmentsPublished.subject=Nouvelles affectations de prière
notification.assignmentsPublished.body=Vous avez été affecté(e) pour les prières suivantes :\n{0}
notification.assignmentsRemoved.subject=Affectations retirées
notification.assignmentsRemoved.body=Vos affectations suivantes ont été retirées suite à une replanification :\n{0}
```

The existing singular `notification.assignmentPublished`/`assignmentRemoved` keys stay — the scheduled
reminder path (`assignmentReminder`, one real event, sent once, close to the date) is a single-item
notification, correctly, and isn't touched by this change. The Notifications screen's body rendering needs
`white-space: pre-line` (or equivalent) so the joined multi-line body actually breaks across lines instead of
collapsing.

## 4. Async invitation email

`MailService` gains a fire-and-forget entry point:

```java
@Async
public void sendEmailAsync(String to, String subject, String textBody, String actionUrl, String actionLabel) {
    try {
        sendEmail(to, subject, textBody, actionUrl, actionLabel);
    } catch (MailException e) {
        LOG.warn("Failed to send email to {}", to, e);
    }
}
```

`AllowedEmailService.sendInvitationEmail` calls this instead of `sendEmail` directly. The try/catch moves
from `AllowedEmailService` into `MailService` itself: an exception thrown inside an `@Async void` method
never reaches the original caller (it goes to `ExceptionHandlingAsyncTaskExecutor`'s handler, i.e. it's
logged, not caught by `AllowedEmailService`'s own try/catch) — so the "log and swallow" behavior has to live
where the exception actually surfaces.

## 5. The missing cache invalidation

Small, independent of everything above: `useGenerateRoster` and `useRescheduleRoster`
(`frontend/src/features/admin/api.ts`) both add `queryClient.invalidateQueries({ queryKey: ["me", "notifications"] })`
to their `onSuccess`, alongside the existing `admin/rosters` and `me/assignments` invalidation.

## Testing notes

- `RosterSolvingService.solveAndApply(Long)` unit tests swap from constructing entities in-memory to
  stubbing repository `findById`/`findByGenerationIdWithSessionAndUser` — same assertions on the outcome,
  different setup.
- The new listeners are trivial (one delegating call each) and are exercised the same way
  `EmailNotificationListener` already is — a unit test asserting the delegate is called, not an integration
  test through the real async executor (this codebase's tests use `AsyncSyncConfiguration` to make `@Async`
  synchronous and deterministic under test).
- `NotificationTextResolver`'s new plural-key branch needs its own unit tests: multi-item body formatting,
  and that the singular keys' behavior is completely unchanged.
- Frontend: a test asserting `refetchInterval` returns a numeric value while the latest generation is
  `RUNNING` and `false` once it's terminal (mocking `useRosterGenerations`'s underlying query state rather
  than waiting on real timers).
