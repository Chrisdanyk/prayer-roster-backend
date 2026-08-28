# Async Roster Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make roster generation and rescheduling non-blocking (the solve moves off the HTTP request thread),
consolidate per-run assignment notifications into one email per recipient instead of one per assignment, and
make invitation email sending non-blocking too.

**Architecture:** Reuses the event + `@Async @TransactionalEventListener(AFTER_COMMIT)` pattern already
proven by `NotificationCreatedEvent`/`EmailNotificationListener`. Each of `RosterGenerationService.generate()`
and `ReschedulingService.reschedule()` keeps its fast synchronous DB-writing phase (validate, create/tag rows,
save) and swaps its inline `RosterSolvingService.solveAndApply(...)` call for publishing
`RosterGenerationRequestedEvent(generationId)`. `RosterSolvingService.solveAndApply` changes from taking
already-loaded entities to taking a `Long generationId` and re-fetching everything itself (made possible
because `PrayerAssignment` already carries a `generation` FK), so it can run standalone on the listener's
thread in a fresh transaction.

**Tech Stack:** Spring Boot, Spring `ApplicationEventPublisher`/`@TransactionalEventListener`, Spring `@Async`
(`AsyncConfiguration`'s `taskExecutor` bean; `AsyncSyncConfiguration` makes it synchronous under test),
JUnit 5 + Mockito, JPA/Hibernate.

**Spec:** `docs/superpowers/specs/2026-08-28-async-roster-operations-design.md`

## Global Constraints

- **JDK 17** required; set `JAVA_HOME` if the default `java` is newer (`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`).
- `./mvnw test` for the fast unit-test loop; this codebase's `*Test` classes are all Mockito/standalone unit
  tests (no real Spring context) — a full `@SpringBootTest` cannot boot here (Timefold's `ConstraintProvider`
  classpath scan conflicts with `SolverServiceTest`'s deliberately-broken second provider). Live-verify by
  booting the packaged jar against a real Postgres, not via a JUnit IT.
- `npm run prettier:format` formats java/yml/json/md before committing (prettier-plugin-java). If it errors
  locally (missing `prettier-plugin-packagejson` in some environments), run `./mvnw checkstyle:check` instead
  to verify formatting/style compliance.
- No AI attribution in commit messages. Commit after each task.
- `Notification.relatedSession`/`relatedAssignment` are nullable and unused by `NotificationDTO`/the
  frontend — safe to leave `null` on batch notifications that don't have one single related assignment.
- The coverage gate (`mvn verify`) wants 100% line/branch on hand-authored business logic — cover every new
  branch (batch vs. singular resolver path, RESCHEDULE vs. other triggers, empty-list edge cases) with a test.

---

### Task 1: `PrayerAssignmentRepository` gains a generation-scoped fetch

**Files:**
- Modify: `src/main/java/com/prayerroster/repository/PrayerAssignmentRepository.java`

**Interfaces:**
- Produces: `List<PrayerAssignment> findByGenerationIdWithSessionAndUser(Long generationId)`

This is the query `RosterSolvingService` will use in Task 5 to re-fetch a generation's working set from just
its id. `user` must be a **left** join-fetch — a fresh generation's assignments have `user == null` until
solved, and an inner join-fetch would silently return zero rows for exactly that case.

- [ ] **Step 1: Add the method**

```java
/**
 * Every assignment tagged with one generation run - the working set {@link
 * com.prayerroster.service.RosterSolvingService} solves and applies. {@code user} is left-joined,
 * not inner-joined: a brand-new generation's assignments are all still unfilled at this point.
 */
@Query("select distinct a from PrayerAssignment a join fetch a.session s left join fetch a.user where a.generation.id = :generationId")
List<PrayerAssignment> findByGenerationIdWithSessionAndUser(@Param("generationId") Long generationId);
```

Add this above the closing brace of the interface, after `findPublishedAssignmentsForDate`.

- [ ] **Step 2: Run `./mvnw -o -q compile`**

Expected: compiles cleanly (no test to write for a repository query in this codebase — see Global
Constraints; every existing custom `@Query` in this repository is exercised only indirectly through the
service tests that mock it, and by live-verifying against the real Postgres jar).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/prayerroster/repository/PrayerAssignmentRepository.java
git commit -m "Add a generation-scoped assignment fetch for the async solve step"
```

---

### Task 2: `RosterGenerationRequestedEvent`

**Files:**
- Create: `src/main/java/com/prayerroster/service/RosterGenerationRequestedEvent.java`

**Interfaces:**
- Produces: `record RosterGenerationRequestedEvent(Long generationId)`

Mirrors the existing `NotificationCreatedEvent` shape exactly (a bare record, same package, same one-field
convention).

- [ ] **Step 1: Create the event**

```java
package com.prayerroster.service;

/**
 * Published once a {@link com.prayerroster.domain.RosterGeneration} row is durably saved with status
 * {@code RUNNING} - {@link RosterSolvingListener} picks this up after commit and runs the actual solve
 * off the request thread (see docs/phase1-architecture.md section 33, and
 * docs/superpowers/specs/2026-08-28-async-roster-operations-design.md).
 */
public record RosterGenerationRequestedEvent(Long generationId) {}
```

- [ ] **Step 2: Run `./mvnw -o -q compile`**

Expected: compiles cleanly.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/prayerroster/service/RosterGenerationRequestedEvent.java
git commit -m "Add the roster-generation-requested event"
```

---

### Task 3: Batch assignment notifications on `NotificationService`

**Files:**
- Modify: `src/main/java/com/prayerroster/service/NotificationService.java`
- Modify: `src/main/resources/i18n/messages_fr.properties`
- Modify: `src/main/resources/i18n/messages_en.properties`
- Test: `src/test/java/com/prayerroster/service/NotificationServiceTest.java`

**Interfaces:**
- Consumes: existing `create(User, NotificationType, String, Map<String,String>, PrayerAssignment)` private
  helper, widened to accept `Object` params.
- Produces:
  - `void notifyAssignmentsPublished(User recipient, List<PrayerAssignment> assignments)`
  - `void notifyAssignmentsRemoved(User recipient, List<PrayerAssignment> assignments)`

One `Notification` row per call, `params` serialized as a JSON **array** of `{date, role}` objects (today's
singular methods serialize a flat object) — this is what Task 4's resolver branches on.

- [ ] **Step 1: Write the failing tests**

Add to `NotificationServiceTest.java`, after `notifyAssignmentReminder_includesDaysBeforeInParams`:

```java
    @Test
    void notifyAssignmentsPublished_savesOneNotificationWithAllItemsInParams() {
        stubSave();
        User recipient = user("u1", "fr");
        PrayerAssignment first = assignment(recipient, PrayerAssignmentRole.MODERATOR);
        PrayerAssignment second = assignment(recipient, PrayerAssignmentRole.PREACHER);
        second.getSession().setDate(LocalDate.of(2026, 9, 13));

        service.notifyAssignmentsPublished(recipient, List.of(first, second));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getRecipient()).isEqualTo(recipient);
        assertThat(saved.getType()).isEqualTo(NotificationType.ASSIGNMENT_PUBLISHED);
        assertThat(saved.getMessageKey()).isEqualTo("notification.assignmentsPublished");
        assertThat(saved.getParams())
            .contains("\"date\":\"2026-09-06\"")
            .contains("\"date\":\"2026-09-13\"")
            .contains("\"role\":\"MODERATOR\"")
            .contains("\"role\":\"PREACHER\"");
        assertThat(saved.getRelatedAssignment()).isNull();
        assertThat(saved.getRelatedSession()).isNull();
    }

    @Test
    void notifyAssignmentsRemoved_targetsThePreviousUserAndBatchesAllItems() {
        stubSave();
        User previousUser = user("u1", "fr");
        User currentUser = user("u2", "fr");
        PrayerAssignment first = assignment(currentUser, PrayerAssignmentRole.MODERATOR);
        PrayerAssignment second = assignment(currentUser, PrayerAssignmentRole.PREACHER);

        service.notifyAssignmentsRemoved(previousUser, List.of(first, second));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getRecipient()).isEqualTo(previousUser);
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.ASSIGNMENT_REMOVED);
        assertThat(captor.getValue().getMessageKey()).isEqualTo("notification.assignmentsRemoved");
    }
```

- [ ] **Step 2: Run, watch them fail**

Run: `./mvnw -o -q test -Dtest=NotificationServiceTest`
Expected: FAIL — `notifyAssignmentsPublished`/`notifyAssignmentsRemoved` don't exist yet (compilation error).

- [ ] **Step 3: Implement**

In `NotificationService.java`, add two new key constants next to the existing ones:

```java
    private static final String KEY_ASSIGNMENTS_PUBLISHED = "notification.assignmentsPublished";
    private static final String KEY_ASSIGNMENTS_REMOVED = "notification.assignmentsRemoved";
```

Add the two new public methods next to `notifyAssignmentRemoved`:

```java
    /** One notification per run per recipient - see docs/phase1-architecture.md section 13. */
    public void notifyAssignmentsPublished(User recipient, List<PrayerAssignment> assignments) {
        create(recipient, NotificationType.ASSIGNMENT_PUBLISHED, KEY_ASSIGNMENTS_PUBLISHED, paramsForBatch(assignments), null);
    }

    public void notifyAssignmentsRemoved(User recipient, List<PrayerAssignment> assignments) {
        create(recipient, NotificationType.ASSIGNMENT_REMOVED, KEY_ASSIGNMENTS_REMOVED, paramsForBatch(assignments), null);
    }
```

Widen `create`'s `params` parameter type from `Map<String, String>` to `Object` (both the flat map and the
new list of maps serialize fine through Jackson either way), and make it tolerate a `null` assignment:

```java
    private void create(User recipient, NotificationType type, String messageKey, Object params, PrayerAssignment assignment) {
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setType(type);
        notification.setMessageKey(messageKey);
        notification.setParams(writeParams(params));
        notification.setRelatedSession(assignment != null ? assignment.getSession() : null);
        notification.setRelatedAssignment(assignment);
        Notification saved = notificationRepository.save(notification);
        eventPublisher.publishEvent(new NotificationCreatedEvent(saved.getId()));
    }
```

Add the batch params builder next to `paramsFor`:

```java
    private List<Map<String, String>> paramsForBatch(List<PrayerAssignment> assignments) {
        return assignments.stream().map(a -> paramsFor(a, null)).toList();
    }
```

And widen `writeParams`'s signature to match:

```java
    private String writeParams(Object params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification params", e);
        }
    }
```

Add `import java.util.List;` if not already present (it is, via other imports in this file — check before
adding a duplicate).

- [ ] **Step 4: Run, verify they pass**

Run: `./mvnw -o -q test -Dtest=NotificationServiceTest`
Expected: PASS, all tests including the two new ones and the pre-existing ones (the widened `create`
signature must not change any existing test's assertions).

- [ ] **Step 5: Add the message bundle entries**

In `src/main/resources/i18n/messages_fr.properties`, after `notification.assignmentReminder.body=...`:

```properties
notification.assignmentsPublished.subject=Nouvelles affectations de prière
notification.assignmentsPublished.body=Vous avez été affecté(e) pour les prières suivantes :\n{0}
notification.assignmentsRemoved.subject=Affectations retirées
notification.assignmentsRemoved.body=Vos affectations suivantes ont été retirées suite à une replanification :\n{0}
```

In `src/main/resources/i18n/messages_en.properties`, after `notification.assignmentReminder.body=...`:

```properties
notification.assignmentsPublished.subject=New prayer assignments
notification.assignmentsPublished.body=You have been assigned for the following prayers:\n{0}
notification.assignmentsRemoved.subject=Assignments removed
notification.assignmentsRemoved.body=Your following assignments were removed due to rescheduling:\n{0}
```

- [ ] **Step 6: Run the whole test suite once**

Run: `./mvnw -o -q test`
Expected: all pass (this step alone doesn't exercise the new keys — Task 4 does — but confirms nothing else
broke).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/prayerroster/service/NotificationService.java \
        src/test/java/com/prayerroster/service/NotificationServiceTest.java \
        src/main/resources/i18n/messages_fr.properties \
        src/main/resources/i18n/messages_en.properties
git commit -m "Batch assignment notifications into one per recipient per run"
```

---

### Task 4: `NotificationTextResolver` renders the batched body

**Files:**
- Modify: `src/main/java/com/prayerroster/service/NotificationTextResolver.java`
- Test: `src/test/java/com/prayerroster/service/NotificationTextResolverTest.java`

**Interfaces:**
- Consumes: the two new message keys added in Task 3 (`notification.assignmentsPublished`/`.assignmentsRemoved`, `.subject`/`.body`)
- Produces: `resolveBody` now handles both the singular (unchanged) and plural (new) message-key shapes; `resolveSubject` needs no change (it only ever does a flat key lookup).

- [ ] **Step 1: Write the failing test**

Add to `NotificationTextResolverTest.java`, after `resolveBody_includesDaysBeforeForReminders`:

```java
    @Test
    void resolveBody_joinsEachItemOnItsOwnLineForABatchedPublishedNotification() {
        Notification notification = notification(
            "notification.assignmentsPublished",
            "[{\"date\":\"2026-09-06\",\"role\":\"MODERATOR\"},{\"date\":\"2026-09-13\",\"role\":\"PREACHER\"}]"
        );

        String frenchBody = resolver.resolveBody(notification, Locale.FRENCH);
        assertThat(frenchBody).contains("modérateur").contains("6 septembre 2026");
        assertThat(frenchBody).contains("prédicateur").contains("13 septembre 2026");
    }

    @Test
    void resolveBody_handlesAnEmptyBatchGracefully() {
        Notification notification = notification("notification.assignmentsRemoved", "[]");

        assertThat(resolver.resolveBody(notification, Locale.FRENCH)).isNotNull();
    }

    @Test
    void resolveBody_handlesMalformedBatchJsonGracefully() {
        Notification notification = notification("notification.assignmentsPublished", "not valid json");

        assertThat(resolver.resolveBody(notification, Locale.FRENCH)).isNotNull();
    }
```

- [ ] **Step 2: Run, watch it fail**

Run: `./mvnw -o -q test -Dtest=NotificationTextResolverTest`
Expected: FAIL on `resolveBody_joinsEachItemOnItsOwnLineForABatchedPublishedNotification` — the current
`resolveBody` calls `parseParams` (expects a flat object), so parsing a JSON array either throws (caught,
returns `Map.of()`) or misbehaves, and the body comes back without the per-item date/role text.

- [ ] **Step 3: Implement**

In `NotificationTextResolver.java`, replace `resolveBody` with a version that branches on the two batch keys:

```java
    private static final String KEY_ASSIGNMENTS_PUBLISHED = "notification.assignmentsPublished";
    private static final String KEY_ASSIGNMENTS_REMOVED = "notification.assignmentsRemoved";

    public String resolveBody(Notification notification, Locale locale) {
        if (isBatchKey(notification.getMessageKey())) {
            return resolveBatchBody(notification, locale);
        }
        Map<String, String> params = parseParams(notification.getParams());
        String date = params.containsKey("date") ? formatDate(params.get("date"), locale) : null;
        String role = params.containsKey("role") ? resolveRole(params.get("role"), locale) : null;
        String daysBefore = params.get("daysBefore");
        return messageSource.getMessage(notification.getMessageKey() + ".body", new Object[] { date, role, daysBefore }, locale);
    }

    private boolean isBatchKey(String key) {
        return KEY_ASSIGNMENTS_PUBLISHED.equals(key) || KEY_ASSIGNMENTS_REMOVED.equals(key);
    }

    private String resolveBatchBody(Notification notification, Locale locale) {
        String lines = parseParamsList(notification.getParams())
            .stream()
            .map(item -> "- " + formatDate(item.get("date"), locale) + " : " + resolveRole(item.get("role"), locale))
            .collect(java.util.stream.Collectors.joining("\n"));
        return messageSource.getMessage(notification.getMessageKey() + ".body", new Object[] { lines }, locale);
    }

    private List<Map<String, String>> parseParamsList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
```

Add `import java.util.List;` at the top of the file (not already present — this file currently only imports
`java.util.Map`).

- [ ] **Step 4: Run, verify all pass**

Run: `./mvnw -o -q test -Dtest=NotificationTextResolverTest`
Expected: PASS, including every pre-existing test (the singular path is untouched).

- [ ] **Step 5: Run the whole test suite, then checkstyle**

Run: `./mvnw -o -q test && ./mvnw -o checkstyle:check`
Expected: all green, 0 checkstyle violations.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/prayerroster/service/NotificationTextResolver.java \
        src/test/java/com/prayerroster/service/NotificationTextResolverTest.java
git commit -m "Render batched assignment notifications as one line per item"
```

---

### Task 5: `RosterSolvingService.solveAndApply` takes a generation id

**Files:**
- Modify: `src/main/java/com/prayerroster/service/RosterSolvingService.java`
- Test: `src/test/java/com/prayerroster/service/RosterSolvingServiceTest.java`

**Interfaces:**
- Consumes: `PrayerAssignmentRepository.findByGenerationIdWithSessionAndUser(Long)` (Task 1),
  `NotificationService.notifyAssignmentsPublished/Removed(User, List<PrayerAssignment>)` (Task 3)
- Produces: `boolean solveAndApply(Long generationId)` — replaces the previous six-argument signature
  entirely. Task 7 and Task 8 depend on this exact new signature.

This is the core rewrite: `solveAndApply` becomes self-contained, deriving everything it needs (roster,
assignments, the solve window, and whether an infeasible solve should fall back to `DRAFT` or
`REQUIRES_RESCHEDULING`) from the generation id alone. The reschedule-only "clear the flag on success" step
(previously done by the caller after `solveAndApply` returned) moves inside, since it depends on the
solve's own outcome plus `generation.getTrigger()`.

- [ ] **Step 1: Write the failing tests**

Replace the whole `RosterSolvingServiceTest.java` body (keep the package/imports block, `testUser`, and
`solvedClone` helpers) — every test changes because the method signature changes:

```java
package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.domain.Roster;
import com.prayerroster.domain.RosterGeneration;
import com.prayerroster.domain.RosterGenerationStatus;
import com.prayerroster.domain.RosterGenerationTrigger;
import com.prayerroster.domain.RosterStatus;
import com.prayerroster.domain.User;
import com.prayerroster.repository.RosterGenerationRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.repository.PrayerAssignmentRepository;
import com.prayerroster.repository.UserAvailabilityRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.scheduling.RosterSolution;
import com.prayerroster.scheduling.SolverService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RosterSolvingServiceTest {

    @Mock
    private RosterRepository rosterRepository;

    @Mock
    private RosterGenerationRepository rosterGenerationRepository;

    @Mock
    private PrayerAssignmentRepository prayerAssignmentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAvailabilityRepository userAvailabilityRepository;

    @Mock
    private SolverService solverService;

    @Mock
    private NotificationService notificationService;

    private RosterSolvingService service;

    @BeforeEach
    void setUp() {
        service = new RosterSolvingService(
            rosterRepository,
            rosterGenerationRepository,
            prayerAssignmentRepository,
            userRepository,
            userAvailabilityRepository,
            solverService,
            notificationService
        );
        when(userAvailabilityRepository.findActiveOverlapping(any(), any())).thenReturn(List.of());
    }

    private static User testUser(String id) {
        User user = new User();
        user.setId(id);
        user.setActive(true);
        user.setCanModerate(true);
        user.setCanPreach(true);
        return user;
    }

    private static PrayerAssignment assignment(Long id, User initialUser) {
        PrayerAssignment assignment = new PrayerAssignment();
        assignment.setId(id);
        assignment.setRole(PrayerAssignmentRole.MODERATOR);
        assignment.setUser(initialUser);
        PrayerSession session = new PrayerSession();
        session.setId(id);
        session.setDate(LocalDate.of(2026, 9, 6));
        assignment.setSession(session);
        return assignment;
    }

    private static Roster roster() {
        Roster roster = new Roster();
        roster.setId(1L);
        roster.setPeriodFrom(LocalDate.of(2026, 9, 1));
        roster.setPeriodTo(LocalDate.of(2026, 9, 30));
        roster.setStatus(RosterStatus.DRAFT);
        return roster;
    }

    private static RosterGeneration generation(Roster roster, RosterGenerationTrigger trigger) {
        RosterGeneration generation = new RosterGeneration();
        generation.setId(9L);
        generation.setRoster(roster);
        generation.setTrigger(trigger);
        generation.setPlanningFrom(roster.getPeriodFrom());
        generation.setPlanningTo(roster.getPeriodTo());
        return generation;
    }

    /**
     * Real Timefold returns a clone of each planning entity from {@code getFinalBestSolution()} -
     * decoupled from the originals passed into the solve, which is exactly what lets {@code
     * RosterSolvingService} safely read an assignment's pre-solve {@code user} before overwriting
     * it. Mutating the original objects directly (instead of returning clones, as a naive mock
     * might) would corrupt that "previous vs. new" comparison - this helper avoids that trap.
     */
    private static PrayerAssignment solvedClone(PrayerAssignment original, User solvedUser) {
        PrayerAssignment clone = new PrayerAssignment();
        clone.setId(original.getId());
        clone.setRole(original.getRole());
        clone.setSession(original.getSession());
        clone.setUser(solvedUser);
        return clone;
    }

    @Test
    void solveAndApply_publishesRosterAndFillsAssignmentsWhenFeasible() {
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.MANUAL);
        PrayerAssignment managed = assignment(1L, null);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1")));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, testUser("u1")))
            );
            solved.setScore(HardSoftScore.of(0, -3));
            return solved;
        });

        boolean feasible = service.solveAndApply(9L);

        assertThat(feasible).isTrue();
        assertThat(managed.getUser()).isNotNull();
        assertThat(roster.getStatus()).isEqualTo(RosterStatus.PUBLISHED);
        assertThat(roster.getPublishedAt()).isNotNull();
        assertThat(generation.getStatus()).isEqualTo(RosterGenerationStatus.COMPLETED);
        assertThat(generation.getHardScore()).isZero();
        assertThat(generation.getSoftScore()).isEqualTo(-3);
        assertThat(generation.getFeasible()).isTrue();
        verify(rosterRepository).save(roster);
        verify(rosterGenerationRepository).save(generation);
    }

    @Test
    void solveAndApply_notifiesOnlyTheNewlyAssignedUserWhenSlotWasPreviouslyEmpty() {
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.MANUAL);
        PrayerAssignment managed = assignment(1L, null);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1")));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, testUser("u1")))
            );
            solved.setScore(HardSoftScore.of(0, 0));
            return solved;
        });

        service.solveAndApply(9L);

        verify(notificationService).notifyAssignmentsPublished(eq(testUser("u1")), eq(List.of(managed)));
        verify(notificationService, never()).notifyAssignmentsRemoved(any(), any());
    }

    @Test
    void solveAndApply_notifiesBothUsersWhenReassignedToADifferentUser() {
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.MANUAL);
        User previousUser = testUser("u1");
        PrayerAssignment managed = assignment(1L, previousUser);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(previousUser, testUser("u2")));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, testUser("u2")))
            );
            solved.setScore(HardSoftScore.of(0, 0));
            return solved;
        });

        service.solveAndApply(9L);

        verify(notificationService).notifyAssignmentsRemoved(eq(previousUser), eq(List.of(managed)));
        verify(notificationService).notifyAssignmentsPublished(eq(testUser("u2")), eq(List.of(managed)));
        assertThat(managed.getUser().getId()).isEqualTo("u2");
    }

    @Test
    void solveAndApply_doesNotNotifyWhenAssignmentIsUnchanged() {
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.MANUAL);
        User sameUser = testUser("u1");
        PrayerAssignment managed = assignment(1L, sameUser);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(sameUser));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, sameUser))
            );
            solved.setScore(HardSoftScore.of(0, 0));
            return solved;
        });

        service.solveAndApply(9L);

        verifyNoInteractions(notificationService);
    }

    @Test
    void solveAndApply_fallsBackToDraftAndRecordsDiagnosticWhenInfeasibleOnAFreshGeneration() {
        Roster roster = roster();
        roster.setStatus(RosterStatus.DRAFT);
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.MANUAL);
        PrayerAssignment managed = assignment(1L, null);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1")));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            problem.setScore(HardSoftScore.of(-2, 0));
            return problem;
        });
        when(solverService.explainHardConstraintViolations(any())).thenReturn("everyAssignmentMustBeFilled: 2 violation(s)");

        boolean feasible = service.solveAndApply(9L);

        assertThat(feasible).isFalse();
        assertThat(roster.getStatus()).isEqualTo(RosterStatus.DRAFT);
        assertThat(generation.getStatus()).isEqualTo(RosterGenerationStatus.INFEASIBLE);
        assertThat(generation.getFeasible()).isFalse();
        assertThat(generation.getErrorMessage()).isEqualTo("everyAssignmentMustBeFilled: 2 violation(s)");
        verifyNoInteractions(notificationService);
    }

    @Test
    void solveAndApply_fallsBackToRequiresReschedulingWhenInfeasibleOnAReschedule() {
        Roster roster = roster();
        roster.setStatus(RosterStatus.REQUIRES_RESCHEDULING);
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.RESCHEDULE);
        PrayerAssignment managed = assignment(1L, null);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1")));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            problem.setScore(HardSoftScore.of(-2, 0));
            return problem;
        });
        when(solverService.explainHardConstraintViolations(any())).thenReturn("everyAssignmentMustBeFilled: 2 violation(s)");

        boolean feasible = service.solveAndApply(9L);

        assertThat(feasible).isFalse();
        assertThat(roster.getStatus()).isEqualTo(RosterStatus.REQUIRES_RESCHEDULING);
    }

    @Test
    void solveAndApply_clearsRequiresReschedulingOnTheAffectedSessionWhenAFeasibleRescheduleSucceeds() {
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.RESCHEDULE);
        PrayerAssignment managed = assignment(1L, testUser("u1"));
        managed.getSession().setRequiresRescheduling(true);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(List.of(managed));
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1"), testUser("u2")));
        when(solverService.solve(eq(9L), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            RosterSolution solved = new RosterSolution(
                problem.getEligibleUsers(),
                problem.getUnavailabilities(),
                List.of(solvedClone(managed, testUser("u2")))
            );
            solved.setScore(HardSoftScore.of(0, 0));
            return solved;
        });

        service.solveAndApply(9L);

        assertThat(managed.getSession().isRequiresRescheduling()).isFalse();
    }

    @Test
    void solveAndApply_skipsSolvingWhenNoEligibleUserExists() {
        Roster roster = roster();
        RosterGeneration generation = generation(roster, RosterGenerationTrigger.MANUAL);
        when(rosterGenerationRepository.findById(9L)).thenReturn(Optional.of(generation));
        when(prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(9L)).thenReturn(
            List.of(assignment(1L, null), assignment(2L, null))
        );
        when(userRepository.findAllEligibleActive()).thenReturn(List.of());

        boolean feasible = service.solveAndApply(9L);

        assertThat(feasible).isFalse();
        assertThat(generation.getHardScore()).isEqualTo(-2);
        assertThat(generation.getSolverDurationMs()).isZero();
        assertThat(generation.getErrorMessage()).contains("No active user has any capability");
        verify(solverService, never()).solve(any(), any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void solveAndApply_throwsWhenGenerationIsMissing() {
        when(rosterGenerationRepository.findById(404L)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
            com.prayerroster.web.rest.errors.EntityNotFoundException.class,
            () -> service.solveAndApply(404L)
        );
    }
}
```

- [ ] **Step 2: Run, watch them fail**

Run: `./mvnw -o -q test -Dtest=RosterSolvingServiceTest`
Expected: FAIL to compile — `solveAndApply(Long)` doesn't exist yet, `PrayerAssignmentRepository` isn't a
constructor argument yet.

- [ ] **Step 3: Implement**

Replace the constructor and `solveAndApply` method in `RosterSolvingService.java`. Add the
`PrayerAssignmentRepository` dependency:

```java
    private final RosterRepository rosterRepository;
    private final RosterGenerationRepository rosterGenerationRepository;
    private final PrayerAssignmentRepository prayerAssignmentRepository;
    private final UserRepository userRepository;
    private final UserAvailabilityRepository userAvailabilityRepository;
    private final SolverService solverService;
    private final NotificationService notificationService;

    public RosterSolvingService(
        RosterRepository rosterRepository,
        RosterGenerationRepository rosterGenerationRepository,
        PrayerAssignmentRepository prayerAssignmentRepository,
        UserRepository userRepository,
        UserAvailabilityRepository userAvailabilityRepository,
        SolverService solverService,
        NotificationService notificationService
    ) {
        this.rosterRepository = rosterRepository;
        this.rosterGenerationRepository = rosterGenerationRepository;
        this.prayerAssignmentRepository = prayerAssignmentRepository;
        this.userRepository = userRepository;
        this.userAvailabilityRepository = userAvailabilityRepository;
        this.solverService = solverService;
        this.notificationService = notificationService;
    }
```

Add `import com.prayerroster.repository.PrayerAssignmentRepository;` and
`import com.prayerroster.web.rest.errors.EntityNotFoundException;` and
`import java.util.LinkedHashMap;` and `import java.util.ArrayList;` to the existing import block.

Replace the whole `solveAndApply` method body with:

```java
    public boolean solveAndApply(Long generationId) {
        RosterGeneration generation = rosterGenerationRepository
            .findById(generationId)
            .orElseThrow(() -> new EntityNotFoundException("Roster generation not found: " + generationId));
        Roster roster = generation.getRoster();
        List<PrayerAssignment> assignments = prayerAssignmentRepository.findByGenerationIdWithSessionAndUser(generationId);
        LocalDate availabilityWindowFrom = generation.getPlanningFrom();
        LocalDate availabilityWindowTo = generation.getPlanningTo();
        RosterStatus statusOnInfeasible = generation.getTrigger() == com.prayerroster.domain.RosterGenerationTrigger.RESCHEDULE
            ? RosterStatus.REQUIRES_RESCHEDULING
            : RosterStatus.DRAFT;

        List<User> eligibleUsers = userRepository.findAllEligibleActive();
        List<UserAvailability> unavailabilities = userAvailabilityRepository.findActiveOverlapping(
            availabilityWindowFrom,
            availabilityWindowTo
        );

        int hardScore;
        int softScore;
        long solverDurationMs;
        RosterSolution solved = null;
        if (eligibleUsers.isEmpty()) {
            hardScore = -assignments.size();
            softScore = 0;
            solverDurationMs = 0;
        } else {
            RosterSolution problem = new RosterSolution(eligibleUsers, unavailabilities, assignments);
            Instant solveStart = Instant.now();
            solved = solverService.solve(generation.getId(), problem);
            solverDurationMs = Duration.between(solveStart, Instant.now()).toMillis();
            hardScore = solved.getScore().hardScore();
            softScore = solved.getScore().softScore();
        }
        generation.setSolverDurationMs(solverDurationMs);
        generation.setHardScore(hardScore);
        generation.setSoftScore(softScore);
        generation.setFeasible(hardScore == 0);

        boolean feasible = hardScore == 0;
        if (feasible) {
            Map<Long, PrayerAssignment> solvedById = solved
                .getAssignments()
                .stream()
                .collect(Collectors.toMap(PrayerAssignment::getId, Function.identity()));
            Map<User, List<PrayerAssignment>> newlyPublished = new LinkedHashMap<>();
            Map<User, List<PrayerAssignment>> newlyRemoved = new LinkedHashMap<>();
            for (PrayerAssignment managed : assignments) {
                User previousUser = managed.getUser();
                User newUser = solvedById.get(managed.getId()).getUser();
                if (!Objects.equals(previousUser, newUser)) {
                    managed.setUser(newUser);
                    if (previousUser != null) {
                        newlyRemoved.computeIfAbsent(previousUser, u -> new ArrayList<>()).add(managed);
                    }
                    newlyPublished.computeIfAbsent(newUser, u -> new ArrayList<>()).add(managed);
                }
            }
            newlyRemoved.forEach(notificationService::notifyAssignmentsRemoved);
            newlyPublished.forEach(notificationService::notifyAssignmentsPublished);

            generation.setStatus(RosterGenerationStatus.COMPLETED);
            roster.setStatus(RosterStatus.PUBLISHED);
            roster.setPublishedAt(Instant.now());
            if (generation.getTrigger() == com.prayerroster.domain.RosterGenerationTrigger.RESCHEDULE) {
                assignments
                    .stream()
                    .map(PrayerAssignment::getSession)
                    .distinct()
                    .filter(PrayerSession::isRequiresRescheduling)
                    .forEach(session -> session.setRequiresRescheduling(false));
            }
        } else {
            generation.setStatus(RosterGenerationStatus.INFEASIBLE);
            generation.setErrorMessage(
                eligibleUsers.isEmpty() ? "No active user has any capability (moderator or preacher)" : solverService.explainHardConstraintViolations(solved)
            );
            roster.setStatus(statusOnInfeasible);
        }

        rosterRepository.save(roster);
        rosterGenerationRepository.save(generation);
        return feasible;
    }
```

Add `import com.prayerroster.domain.PrayerSession;` if not already present (it is not — check the existing
import list before adding a duplicate).

- [ ] **Step 4: Run, verify they pass**

Run: `./mvnw -o -q test -Dtest=RosterSolvingServiceTest`
Expected: PASS, all 9 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/prayerroster/service/RosterSolvingService.java \
        src/test/java/com/prayerroster/service/RosterSolvingServiceTest.java
git commit -m "Make solveAndApply self-contained: take a generation id, not loaded entities"
```

---

### Task 6: `RosterSolvingListener` runs the solve after commit

**Files:**
- Create: `src/main/java/com/prayerroster/service/RosterSolvingListener.java`
- Test: `src/test/java/com/prayerroster/service/RosterSolvingListenerTest.java`

**Interfaces:**
- Consumes: `RosterGenerationRequestedEvent` (Task 2), `RosterSolvingService.solveAndApply(Long)` (Task 5)

Same shape as `EmailNotificationListener`/`EmailNotificationListenerTest` exactly.

- [ ] **Step 1: Write the failing test**

```java
package com.prayerroster.service;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RosterSolvingListenerTest {

    @Mock
    private RosterSolvingService rosterSolvingService;

    private RosterSolvingListener listener;

    @BeforeEach
    void setUp() {
        listener = new RosterSolvingListener(rosterSolvingService);
    }

    @Test
    void onRosterGenerationRequested_delegatesToRosterSolvingService() {
        listener.onRosterGenerationRequested(new RosterGenerationRequestedEvent(42L));

        verify(rosterSolvingService).solveAndApply(42L);
    }
}
```

- [ ] **Step 2: Run, watch it fail**

Run: `./mvnw -o -q test -Dtest=RosterSolvingListenerTest`
Expected: FAIL to compile — `RosterSolvingListener` doesn't exist yet.

- [ ] **Step 3: Implement**

```java
package com.prayerroster.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs the actual Timefold solve off the request thread, after the generation row that requested it
 * is durably committed - same shape as {@link EmailNotificationListener} (see
 * docs/superpowers/specs/2026-08-28-async-roster-operations-design.md). {@link
 * RosterSolvingService#solveAndApply} is itself {@code @Transactional}, so this runs in a genuinely
 * fresh transaction on this new thread.
 */
@Component
public class RosterSolvingListener {

    private final RosterSolvingService rosterSolvingService;

    public RosterSolvingListener(RosterSolvingService rosterSolvingService) {
        this.rosterSolvingService = rosterSolvingService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRosterGenerationRequested(RosterGenerationRequestedEvent event) {
        rosterSolvingService.solveAndApply(event.generationId());
    }
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `./mvnw -o -q test -Dtest=RosterSolvingListenerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/prayerroster/service/RosterSolvingListener.java \
        src/test/java/com/prayerroster/service/RosterSolvingListenerTest.java
git commit -m "Add the listener that runs a requested roster solve off the request thread"
```

---

### Task 7: `RosterGenerationService.generate()` publishes instead of solving inline

**Files:**
- Modify: `src/main/java/com/prayerroster/service/RosterGenerationService.java`
- Test: `src/test/java/com/prayerroster/service/RosterGenerationServiceTest.java`

**Interfaces:**
- Consumes: `ApplicationEventPublisher` (Spring-provided), `RosterGenerationRequestedEvent` (Task 2)
- Produces: `generate(...)` still returns `RosterDTO`, but it now always reflects the freshly-created,
  not-yet-solved roster (`status == DRAFT`) — every existing test that asserted a post-solve `PUBLISHED`
  status or a `verify(rosterSolvingService).solveAndApply(...)` call is now testing behavior that no longer
  exists in this class and must change.

- [ ] **Step 1: Write the failing tests**

In `RosterGenerationServiceTest.java`:

Replace the `@Mock private RosterSolvingService rosterSolvingService;` field and the constructor call in
`setUp()` with an `ApplicationEventPublisher` mock:

```java
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private RosterGenerationService service;

    @BeforeEach
    void setUp() {
        service = new RosterGenerationService(
            rosterRepository,
            rosterGenerationRepository,
            prayerSessionRepository,
            prayerAssignmentRepository,
            weeklyPrayerConfigurationRepository,
            eventPublisher
        );
    }
```

Delete the `stubFeasibleSolve()` helper entirely (there is no solve to stub anymore) and every call to it.

Replace `generate_createsSessionsWithBothRolesOnPreachingDaysAndModeratorOnlyOtherwiseAndDelegatesSolving`'s
body from `RosterDTO result = service.generate(request);` onward with:

```java
        RosterDTO result = service.generate(request);

        assertThat(result.status()).isEqualTo(RosterStatus.DRAFT);
        assertThat(result.periodFrom()).isEqualTo(request.from());
        assertThat(result.periodTo()).isEqualTo(request.to());

        ArgumentCaptor<RosterGenerationRequestedEvent> eventCaptor = ArgumentCaptor.forClass(RosterGenerationRequestedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().generationId()).isNotNull();
```

(keep the rest of that test — the `PrayerSession`/`PrayerAssignment` assertions below are unaffected).

Delete `generate_reflectsRosterStateWhenSolvingReportsInfeasible` entirely — `generate()` can no longer know
the solve's outcome, so there's nothing left for this test to assert.

Every other test in the file (`generate_rejectsWhenFromAfterTo`,
`generate_rejectsWhenPeriodOverlapsExistingSessions`, `generate_rejectsWhenNoConfigurationCoversTheDate`,
`generate_picksTheConfigVersionThatCoversEachDateWhenTheConfigChangedMidPeriod`,
`generate_rejectsWhenDateFallsAfterTheOnlyVersionsClosedRange`,
`generate_rejectsWhenApplicableVersionIsMissingADaySetting`, `generate_singleArgOverloadDefaultsTriggerToManual`,
`generate_withExplicitTriggerSetsItOnTheGeneration`) needs its `stubSaves(); stubFeasibleSolve();` call
changed to just `stubSaves();` (drop the deleted helper call) — none of them assert on the solve outcome.

- [ ] **Step 2: Run, watch them fail**

Run: `./mvnw -o -q test -Dtest=RosterGenerationServiceTest`
Expected: FAIL to compile — the constructor signature and `stubFeasibleSolve` references don't match yet.

- [ ] **Step 3: Implement**

In `RosterGenerationService.java`, replace the `RosterSolvingService` field/constructor parameter with
`ApplicationEventPublisher`:

```java
    private final RosterRepository rosterRepository;
    private final RosterGenerationRepository rosterGenerationRepository;
    private final PrayerSessionRepository prayerSessionRepository;
    private final PrayerAssignmentRepository prayerAssignmentRepository;
    private final WeeklyPrayerConfigurationRepository weeklyPrayerConfigurationRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RosterGenerationService(
        RosterRepository rosterRepository,
        RosterGenerationRepository rosterGenerationRepository,
        PrayerSessionRepository prayerSessionRepository,
        PrayerAssignmentRepository prayerAssignmentRepository,
        WeeklyPrayerConfigurationRepository weeklyPrayerConfigurationRepository,
        ApplicationEventPublisher eventPublisher
    ) {
        this.rosterRepository = rosterRepository;
        this.rosterGenerationRepository = rosterGenerationRepository;
        this.prayerSessionRepository = prayerSessionRepository;
        this.prayerAssignmentRepository = prayerAssignmentRepository;
        this.weeklyPrayerConfigurationRepository = weeklyPrayerConfigurationRepository;
        this.eventPublisher = eventPublisher;
    }
```

Add `import org.springframework.context.ApplicationEventPublisher;`, and remove the now-unused
`RosterSolvingService` import.

Replace the last two lines of `generate(...)`:

```java
        rosterSolvingService.solveAndApply(roster, generation, assignments, from, to, RosterStatus.DRAFT);

        return RosterDTO.from(roster);
```

with:

```java
        eventPublisher.publishEvent(new RosterGenerationRequestedEvent(generation.getId()));

        return RosterDTO.from(roster);
```

- [ ] **Step 4: Run, verify they pass**

Run: `./mvnw -o -q test -Dtest=RosterGenerationServiceTest`
Expected: PASS.

- [ ] **Step 5: Run the whole suite**

Run: `./mvnw -o -q test`
Expected: all pass (this is the first point where `RosterSolvingService`'s constructor argument order change
from Task 5 and this class's caller change must agree — a full run catches any other caller this plan
missed).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/prayerroster/service/RosterGenerationService.java \
        src/test/java/com/prayerroster/service/RosterGenerationServiceTest.java
git commit -m "Generate a roster by requesting a solve, not running one inline"
```

---

### Task 8: `ReschedulingService.reschedule()` publishes instead of solving inline

**Files:**
- Modify: `src/main/java/com/prayerroster/service/ReschedulingService.java`
- Test: `src/test/java/com/prayerroster/service/ReschedulingServiceTest.java`

**Interfaces:**
- Consumes: `ApplicationEventPublisher`, `RosterGenerationRequestedEvent` (Task 2)
- Produces: `reschedule(...)` still returns `RosterDTO`, reflecting the roster before the solve runs. The
  session-flag-clearing that used to happen here on a feasible outcome now lives inside
  `RosterSolvingService.solveAndApply` (Task 5, already covered by
  `solveAndApply_clearsRequiresReschedulingOnTheAffectedSessionWhenAFeasibleRescheduleSucceeds`) — this
  class no longer needs to know the outcome at all.

- [ ] **Step 1: Write the failing tests**

In `ReschedulingServiceTest.java`, replace the `@Mock private RosterSolvingService rosterSolvingService;`
field and `setUp()`:

```java
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private ReschedulingService service;

    @BeforeEach
    void setUp() {
        service = new ReschedulingService(rosterRepository, rosterGenerationRepository, prayerSessionRepository, eventPublisher);
    }
```

Replace `reschedule_pinsUnflaggedAssignmentsAndDelegatesSolving`'s body from
`when(rosterSolvingService...)` onward:

```java
    @Test
    void reschedule_pinsUnflaggedAssignmentsAndPublishesTheSolveRequest() {
        stubGenerationSave();
        Roster roster = roster(RosterStatus.REQUIRES_RESCHEDULING);
        PrayerSession flagged = session(1L, true);
        PrayerSession unflagged = session(2L, false);
        when(rosterRepository.findById(1L)).thenReturn(Optional.of(roster));
        when(prayerSessionRepository.findByRosterIdWithAssignments(1L)).thenReturn(List.of(flagged, unflagged));

        RosterDTO result = service.reschedule(1L, "Indisponibilité");

        assertThat(result.id()).isEqualTo(1L);
        PrayerAssignment flaggedAssignment = flagged.getAssignments().iterator().next();
        PrayerAssignment unflaggedAssignment = unflagged.getAssignments().iterator().next();
        assertThat(flaggedAssignment.isLocked()).isFalse();
        assertThat(unflaggedAssignment.isLocked()).isTrue();

        ArgumentCaptor<RosterGeneration> captor = ArgumentCaptor.forClass(RosterGeneration.class);
        verify(rosterGenerationRepository).save(captor.capture());
        RosterGeneration generation = captor.getValue();
        assertThat(generation.getTrigger()).isEqualTo(RosterGenerationTrigger.RESCHEDULE);
        assertThat(generation.isRegenerated()).isTrue();
        assertThat(generation.getRescheduleReason()).isEqualTo("Indisponibilité");
        assertThat(flaggedAssignment.getGeneration()).isEqualTo(generation);
        assertThat(unflaggedAssignment.getGeneration()).isEqualTo(generation);

        ArgumentCaptor<RosterGenerationRequestedEvent> eventCaptor = ArgumentCaptor.forClass(RosterGenerationRequestedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().generationId()).isEqualTo(generation.getId());
    }
```

Delete `reschedule_leavesSessionFlaggedWhenSolveStaysInfeasible` entirely — that behavior moved to
`RosterSolvingService` and is covered there (Task 5's `_fallsBackToRequiresReschedulingWhenInfeasibleOnAReschedule`).

- [ ] **Step 2: Run, watch it fail**

Run: `./mvnw -o -q test -Dtest=ReschedulingServiceTest`
Expected: FAIL to compile — constructor signature mismatch.

- [ ] **Step 3: Implement**

In `ReschedulingService.java`, replace the `RosterSolvingService` field/constructor parameter:

```java
    private final RosterRepository rosterRepository;
    private final RosterGenerationRepository rosterGenerationRepository;
    private final PrayerSessionRepository prayerSessionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ReschedulingService(
        RosterRepository rosterRepository,
        RosterGenerationRepository rosterGenerationRepository,
        PrayerSessionRepository prayerSessionRepository,
        ApplicationEventPublisher eventPublisher
    ) {
        this.rosterRepository = rosterRepository;
        this.rosterGenerationRepository = rosterGenerationRepository;
        this.prayerSessionRepository = prayerSessionRepository;
        this.eventPublisher = eventPublisher;
    }
```

Add `import org.springframework.context.ApplicationEventPublisher;`, remove the now-unused
`RosterSolvingService` import.

Replace the tail of `reschedule(...)`, from `boolean feasible = rosterSolvingService.solveAndApply(...)`
through the end of the method:

```java
        eventPublisher.publishEvent(new RosterGenerationRequestedEvent(savedGeneration.getId()));

        return RosterDTO.from(roster);
```

- [ ] **Step 4: Run, verify it passes**

Run: `./mvnw -o -q test -Dtest=ReschedulingServiceTest`
Expected: PASS.

- [ ] **Step 5: Run the whole suite, then checkstyle**

Run: `./mvnw -o -q test && ./mvnw -o checkstyle:check`
Expected: all green, 0 violations.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/prayerroster/service/ReschedulingService.java \
        src/test/java/com/prayerroster/service/ReschedulingServiceTest.java
git commit -m "Reschedule by requesting a solve, not running one inline"
```

---

### Task 9: Async invitation email

**Files:**
- Modify: `src/main/java/com/prayerroster/service/MailService.java`
- Modify: `src/main/java/com/prayerroster/service/AllowedEmailService.java`
- Test: `src/test/java/com/prayerroster/service/MailServiceTest.java`
- Test: `src/test/java/com/prayerroster/service/AllowedEmailServiceTest.java`

**Interfaces:**
- Produces: `MailService.sendEmailAsync(String to, String subject, String textBody, String actionUrl, String actionLabel)` — `@Async void`, catches and logs `MailException` itself (an exception thrown from an `@Async void` method never reaches the original caller, so the catch has to live here, not in the caller).
- Consumes (in `AllowedEmailService`): the above, replacing the direct `sendEmail(...)` call.

- [ ] **Step 1: Write the failing test for `MailService`**

Add to `MailServiceTest.java`, after `sendEmail_withAction_passesTheLinkAndLabelToTheTemplate`:

```java
    @Test
    void sendEmailAsync_sendsSuccessfully() throws Exception {
        when(javaMailSender.createMimeMessage()).thenReturn(realMimeMessage());
        when(templateEngine.process(eq("mail/notification"), any())).thenReturn("<p>Corps</p>");

        service.sendEmailAsync("jean@example.com", "Sujet", "Corps", "https://app.example.com", "Se connecter");

        verify(javaMailSender).send(any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    void sendEmailAsync_swallowsAndLogsAFailureRatherThanPropagating() {
        when(javaMailSender.createMimeMessage()).thenReturn(realMimeMessage());
        when(templateEngine.process(eq("mail/notification"), any())).thenReturn("<p>Corps</p>");
        doThrow(new MailSendException("boom")).when(javaMailSender).send(any(MimeMessage.class));

        // Must not throw - an @Async void method's exception would otherwise be lost to the
        // uncaught-exception handler instead of being logged predictably.
        service.sendEmailAsync("jean@example.com", "Sujet", "Corps", null, null);
    }
```

- [ ] **Step 2: Run, watch it fail**

Run: `./mvnw -o -q test -Dtest=MailServiceTest`
Expected: FAIL to compile — `sendEmailAsync` doesn't exist yet.

- [ ] **Step 3: Implement `MailService.sendEmailAsync`**

Add to `MailService.java`, after the 5-argument `sendEmail`:

```java
    /**
     * Fire-and-forget entry point for callers with no natural {@link
     * com.prayerroster.domain.Notification} row to hang a retry sweep off (see
     * {@link AllowedEmailService}). An exception thrown here never reaches the original caller -
     * {@code @Async void} methods report failures to the executor's uncaught-exception handler, not
     * back to the call site - so the catch has to live in this method, not in the caller.
     */
    @org.springframework.scheduling.annotation.Async
    public void sendEmailAsync(String to, String subject, String textBody, String actionUrl, String actionLabel) {
        try {
            sendEmail(to, subject, textBody, actionUrl, actionLabel);
        } catch (MailSendException e) {
            LOG.warn("Failed to send email to {}", to, e);
        }
    }
```

- [ ] **Step 4: Run, verify `MailServiceTest` passes**

Run: `./mvnw -o -q test -Dtest=MailServiceTest`
Expected: PASS.

- [ ] **Step 5: Update `AllowedEmailService` to call the async entry point**

In `AllowedEmailServiceTest.java`, find `sendInvitationEmail`'s tests (`invite_sendsAnInvitationEmailWithASignInLink`, `invite_omitsTheLinkWhenNoFrontendBaseUrlIsConfigured`, `resend_sendsTheInvitationEmailAgain`) and change every
`verify(mailService).sendEmail(...)` to `verify(mailService).sendEmailAsync(...)` (same arguments,
different method name).

Delete `invite_stillSucceedsWhenTheInvitationEmailFailsToSend` entirely — that failure-handling behavior
moved into `MailService.sendEmailAsync` (Step 3) and is covered by
`sendEmailAsync_swallowsAndLogsAFailureRatherThanPropagating` in `MailServiceTest`; `AllowedEmailService`
itself no longer catches anything.

- [ ] **Step 6: Run, watch the renamed-verify tests fail**

Run: `./mvnw -o -q test -Dtest=AllowedEmailServiceTest`
Expected: FAIL — `sendInvitationEmail` still calls `sendEmail`, not `sendEmailAsync`.

- [ ] **Step 7: Implement**

In `AllowedEmailService.java`, simplify `sendInvitationEmail` to drop the try/catch (it's dead now — nothing
thrown from `sendEmailAsync` can reach here) and call the new method:

```java
    private void sendInvitationEmail(String email) {
        String subject = messageSource.getMessage("invitation.email.subject", null, Locale.FRENCH);
        String body = messageSource.getMessage("invitation.email.body", null, Locale.FRENCH);
        String cta = messageSource.getMessage("invitation.email.cta", null, Locale.FRENCH);
        String baseUrl = applicationProperties.getFrontend().getBaseUrl();
        String actionUrl = StringUtils.hasText(baseUrl) ? baseUrl : null;
        String actionLabel = actionUrl != null ? cta : null;
        mailService.sendEmailAsync(email, subject, body, actionUrl, actionLabel);
    }
```

Remove the now-unused `LOG`, `Logger`/`LoggerFactory` imports and the `MailException` import if nothing else
in the file uses them (check: `LOG` was only ever used inside the removed catch block).

- [ ] **Step 8: Run, verify `AllowedEmailServiceTest` passes**

Run: `./mvnw -o -q test -Dtest=AllowedEmailServiceTest`
Expected: PASS.

- [ ] **Step 9: Run the whole suite, checkstyle, and package**

```bash
./mvnw -o -q test
./mvnw -o checkstyle:check
./mvnw -o -q package -DskipTests
```

Expected: all green, jar builds.

- [ ] **Step 10: Live-verify**

Restart the backend (`./run-local.sh`, rebuilding the jar first if it's stale — this project's own
convention, since a full `@SpringBootTest` can't boot here). Invite a test address through the Invitations
screen; confirm the email still arrives in maildev with the same template as before, and that the HTTP
response for the invite comes back without waiting on the SMTP round-trip.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/prayerroster/service/MailService.java \
        src/main/java/com/prayerroster/service/AllowedEmailService.java \
        src/test/java/com/prayerroster/service/MailServiceTest.java \
        src/test/java/com/prayerroster/service/AllowedEmailServiceTest.java
git commit -m "Send invitation email asynchronously"
```

---

### Task 10: Full verification and live check

**Files:** none (verification only)

- [ ] **Step 1: Run the full backend gate**

```bash
./mvnw -o -q test
./mvnw -o checkstyle:check
./mvnw -o -q package -DskipTests
```

Expected: all green.

- [ ] **Step 2: Live-verify async generation**

Rebuild and restart the backend jar. From the admin Plannings screen, generate a roster for a period that
doesn't overlap any existing one. Confirm: the dialog closes quickly (no multi-second wait), the roster
lands on its detail page still `DRAFT`, and within a few seconds `GET /api/rosters/{id}/generations` (or the
detail page, once Task 2 of the frontend plan lands) shows the generation flip to `COMPLETED`/`INFEASIBLE`
and the roster to `PUBLISHED` if feasible.

- [ ] **Step 3: Live-verify consolidated notifications**

Check maildev after that generation: a person assigned to multiple sessions in the new period should receive
exactly one `assignmentsPublished` email listing every date/role, not one email per assignment.

- [ ] **Step 4: Live-verify reschedule**

Flag a session `requiresRescheduling` (or wait for `ReschedulingDetectionService` to do it), call
`POST /api/rosters/{id}/reschedule`, and confirm the same async pattern: the request returns quickly, the
generation appears `RUNNING` then flips to a terminal status, and on success the previously-flagged
session's `requiresRescheduling` clears.
