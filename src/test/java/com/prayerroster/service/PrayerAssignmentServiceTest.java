package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.repository.PrayerAssignmentRepository;
import com.prayerroster.service.dto.ConflictingAssignmentDTO;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrayerAssignmentServiceTest {

    @Mock
    private PrayerAssignmentRepository prayerAssignmentRepository;

    private PrayerAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new PrayerAssignmentService(prayerAssignmentRepository);
    }

    private static PrayerAssignment assignment(Long id, LocalDate date, PrayerAssignmentRole role) {
        PrayerAssignment assignment = new PrayerAssignment();
        assignment.setId(id);
        assignment.setRole(role);
        PrayerSession session = new PrayerSession();
        session.setDate(date);
        assignment.setSession(session);
        return assignment;
    }

    private static PrayerAssignment assignmentOn(LocalDate date, PrayerAssignmentRole role) {
        return assignment(null, date, role);
    }

    @Test
    void findOwnUpcoming_returnsSortedByDate() {
        when(prayerAssignmentRepository.findPublishedAssignmentsForUserInRange(eq("u1"), any(), any())).thenReturn(
            List.of(
                assignment(2L, LocalDate.of(2026, 9, 13), PrayerAssignmentRole.PREACHER),
                assignment(1L, LocalDate.of(2026, 9, 6), PrayerAssignmentRole.MODERATOR)
            )
        );

        var result = service.findOwnUpcoming("u1");

        assertThat(result).extracting("id").containsExactly(1L, 2L);
    }

    @Test
    void findOwnUpcoming_queriesFromTodayForwardSixMonths() {
        when(prayerAssignmentRepository.findPublishedAssignmentsForUserInRange(eq("u1"), any(), any())).thenReturn(List.of());

        service.findOwnUpcoming("u1");

        verify(prayerAssignmentRepository).findPublishedAssignmentsForUserInRange(
            eq("u1"),
            eq(LocalDate.now()),
            eq(LocalDate.now().plusMonths(PrayerAssignmentService.UPCOMING_HORIZON_MONTHS))
        );
    }

    @Test
    void findOwnUpcoming_returnsEmptyListWhenNoneFound() {
        when(prayerAssignmentRepository.findPublishedAssignmentsForUserInRange(eq("u1"), any(), any())).thenReturn(List.of());

        assertThat(service.findOwnUpcoming("u1")).isEmpty();
    }

    @Test
    void findOwnConflicts_returnsPublishedAssignmentsInsideTheProposedRange() {
        PrayerAssignment assignment = assignmentOn(LocalDate.parse("2026-09-16"), PrayerAssignmentRole.MODERATOR);
        when(
            prayerAssignmentRepository.findPublishedAssignmentsForUserInRange(
                "sub-1",
                LocalDate.parse("2026-09-14"),
                LocalDate.parse("2026-09-18")
            )
        ).thenReturn(List.of(assignment));

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
        when(
            prayerAssignmentRepository.findPublishedAssignmentsForUserInRange(
                "sub-1",
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-02")
            )
        ).thenReturn(List.of());

        assertThat(service.findOwnConflicts("sub-1", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"))).isEmpty();
    }
}
