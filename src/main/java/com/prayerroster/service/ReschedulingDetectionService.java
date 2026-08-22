package com.prayerroster.service;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.Roster;
import com.prayerroster.domain.RosterStatus;
import com.prayerroster.repository.PrayerAssignmentRepository;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finds published-roster assignments affected by a user becoming unavailable or inactive, flags
 * their sessions and rosters, and triggers an immediate pinned re-solve for each affected roster
 * (see docs/phase1-architecture.md section 12). Must only ever be called after the triggering
 * change (the new availability row, the deactivation) has already committed - see {@link
 * ReschedulingDetectionListener}, which waits for exactly that.
 * <p>
 * Runs in its own transaction (REQUIRES_NEW via {@link TransactionTemplate}, not an annotation) -
 * live testing surfaced a real bug here: calling an {@code @Transactional} method from inside a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} callback relies on Spring correctly
 * resolving "no transaction is currently active" at that exact point to start a fresh one, and in
 * practice that resolution was unreliable immediately after the triggering transaction's own
 * commit - the reschedule ran (a real Timefold solve, sequence values consumed for the new
 * generation row) but its writes were never durably committed, with no exception anywhere to catch.
 * An explicit {@code TransactionTemplate} with {@code PROPAGATION_REQUIRES_NEW} sidesteps that
 * ambiguity entirely by always starting a genuinely new, independent transaction - proven to
 * actually commit via a live end-to-end run against a real Postgres, not just by the absence of a
 * thrown exception.
 * <p>
 * Weekly-configuration changes are deliberately not a trigger here: {@link
 * com.prayerroster.domain.PrayerSession#isRequiresPreacher()} is snapshotted at generation time and
 * never retroactively altered by a later configuration edit (see
 * docs/phase1-architecture.md sections 4-5), so an already-published session's requirements can
 * never actually change out from under it - there is nothing for a config edit to invalidate.
 * <p>
 * Never lets a rescheduling failure propagate to its caller: the triggering action has already
 * succeeded and must not be undermined by a downstream solve failure. A failure here simply leaves
 * the affected roster(s) flagged {@code REQUIRES_RESCHEDULING} for a manual retry.
 */
@Service
public class ReschedulingDetectionService {

    private static final Logger LOG = LoggerFactory.getLogger(ReschedulingDetectionService.class);

    private final PrayerAssignmentRepository prayerAssignmentRepository;
    private final ReschedulingService reschedulingService;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ReschedulingDetectionService(
        PrayerAssignmentRepository prayerAssignmentRepository,
        ReschedulingService reschedulingService,
        PlatformTransactionManager transactionManager
    ) {
        this.prayerAssignmentRepository = prayerAssignmentRepository;
        this.reschedulingService = reschedulingService;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void onAvailabilityCreated(String userId, LocalDate startDate, LocalDate endDate) {
        LocalDate from = startDate.isBefore(LocalDate.now()) ? LocalDate.now() : startDate;
        if (from.isAfter(endDate)) {
            return;
        }
        runIsolated(userId, () -> flagAndReschedule(userId, from, endDate));
    }

    public void onUserDeactivated(String userId) {
        runIsolated(userId, () -> flagAndReschedule(userId, LocalDate.now(), LocalDate.MAX));
    }

    private void runIsolated(String userId, Runnable action) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> action.run());
        } catch (RuntimeException e) {
            LOG.warn("Automatic rescheduling failed for user {} - affected roster(s) remain flagged for manual reschedule", userId, e);
        }
    }

    private void flagAndReschedule(String userId, LocalDate from, LocalDate to) {
        List<PrayerAssignment> affected = prayerAssignmentRepository.findPublishedAssignmentsForUserInRange(userId, from, to);
        if (affected.isEmpty()) {
            return;
        }

        Set<Roster> affectedRosters = new LinkedHashSet<>();
        for (PrayerAssignment assignment : affected) {
            assignment.getSession().setRequiresRescheduling(true);
            affectedRosters.add(assignment.getSession().getRoster());
        }
        affectedRosters.forEach(roster -> roster.setStatus(RosterStatus.REQUIRES_RESCHEDULING));

        for (Roster roster : affectedRosters) {
            reschedulingService.reschedule(
                roster.getId(),
                "Redéclenché automatiquement suite à un changement de disponibilité ou de statut d'un utilisateur"
            );
        }
    }
}
