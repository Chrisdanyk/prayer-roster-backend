package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import com.prayerroster.domain.*;
import com.prayerroster.repository.PrayerAssignmentRepository;
import com.prayerroster.repository.PrayerSessionRepository;
import com.prayerroster.repository.RosterGenerationRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.repository.UserAvailabilityRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.repository.WeeklyPrayerConfigurationRepository;
import com.prayerroster.scheduling.RosterSolution;
import com.prayerroster.scheduling.SolverService;
import com.prayerroster.service.dto.GenerateRosterRequest;
import com.prayerroster.service.dto.RosterDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RosterGenerationServiceTest {

    @Mock
    private RosterRepository rosterRepository;

    @Mock
    private RosterGenerationRepository rosterGenerationRepository;

    @Mock
    private PrayerSessionRepository prayerSessionRepository;

    @Mock
    private PrayerAssignmentRepository prayerAssignmentRepository;

    @Mock
    private WeeklyPrayerConfigurationRepository weeklyPrayerConfigurationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAvailabilityRepository userAvailabilityRepository;

    @Mock
    private SolverService solverService;

    private RosterGenerationService service;

    @BeforeEach
    void setUp() {
        service = new RosterGenerationService(
            rosterRepository,
            rosterGenerationRepository,
            prayerSessionRepository,
            prayerAssignmentRepository,
            weeklyPrayerConfigurationRepository,
            userRepository,
            userAvailabilityRepository,
            solverService
        );
    }

    private static WeeklyPrayerConfiguration configVersion(LocalDate effectiveFrom, LocalDate effectiveTo, Set<DayOfWeek> preachingDays) {
        WeeklyPrayerConfiguration config = new WeeklyPrayerConfiguration();
        config.setEffectiveFrom(effectiveFrom);
        config.setEffectiveTo(effectiveTo);
        Set<WeeklyPrayerConfigurationDay> days = new HashSet<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            WeeklyPrayerConfigurationDay day = new WeeklyPrayerConfigurationDay();
            day.setConfiguration(config);
            day.setDayOfWeek(d);
            day.setRequiresPreacher(preachingDays.contains(d));
            days.add(day);
        }
        config.setDays(days);
        return config;
    }

    private static User testUser(String id) {
        User user = new User();
        user.setId(id);
        user.setActive(true);
        user.setCanModerate(true);
        user.setCanPreach(true);
        return user;
    }

    private void stubSaves() {
        AtomicLong rosterIds = new AtomicLong(1);
        when(rosterRepository.save(any(Roster.class))).thenAnswer(inv -> {
            Roster r = inv.getArgument(0);
            r.setId(rosterIds.getAndIncrement());
            return r;
        });
        AtomicLong generationIds = new AtomicLong(1);
        when(rosterGenerationRepository.save(any(RosterGeneration.class))).thenAnswer(inv -> {
            RosterGeneration g = inv.getArgument(0);
            if (g.getId() == null) {
                g.setId(generationIds.getAndIncrement());
            }
            return g;
        });
        when(prayerSessionRepository.save(any(PrayerSession.class))).thenAnswer(inv -> inv.getArgument(0));
        AtomicLong assignmentIds = new AtomicLong(1);
        when(prayerAssignmentRepository.save(any(PrayerAssignment.class))).thenAnswer(inv -> {
            PrayerAssignment a = inv.getArgument(0);
            a.setId(assignmentIds.getAndIncrement());
            return a;
        });
        when(userRepository.findAllEligibleActive()).thenReturn(List.of(testUser("u1")));
        when(userAvailabilityRepository.findActiveOverlapping(any(), any())).thenReturn(List.of());
    }

    /** By default, the mocked solve fills every assignment with a fixed user and reports feasible. */
    private void stubFeasibleSolve() {
        when(solverService.solve(any(), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            User user = testUser("u1");
            problem.getAssignments().forEach(a -> a.setUser(user));
            problem.setScore(HardSoftScore.of(0, -1));
            return problem;
        });
    }

    @Test
    void generate_rejectsWhenFromAfterTo() {
        var request = new GenerateRosterRequest(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> service.generate(request)).isInstanceOf(BadRequestAlertException.class);
        verify(prayerSessionRepository, never()).existsByDateBetween(any(), any());
    }

    @Test
    void generate_rejectsWhenPeriodOverlapsExistingSessions() {
        var request = new GenerateRosterRequest(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7));
        when(prayerSessionRepository.existsByDateBetween(request.from(), request.to())).thenReturn(true);

        assertThatThrownBy(() -> service.generate(request)).isInstanceOf(BadRequestAlertException.class);
        verify(rosterRepository, never()).save(any());
    }

    @Test
    void generate_rejectsWhenNoConfigurationCoversTheDate() {
        var request = new GenerateRosterRequest(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));
        when(prayerSessionRepository.existsByDateBetween(any(), any())).thenReturn(false);
        when(weeklyPrayerConfigurationRepository.findAllWithDaysOrderByEffectiveFromDesc()).thenReturn(List.of());

        assertThatThrownBy(() -> service.generate(request)).isInstanceOf(BadRequestAlertException.class);
        verify(rosterRepository, never()).save(any());
    }

    @Test
    void generate_createsSessionsWithBothRolesOnPreachingDaysAndModeratorOnlyOtherwiseAndPublishesOnFeasibleSolve() {
        stubSaves();
        stubFeasibleSolve();
        // 2026-09-06 is a Sunday (preaching day), 2026-09-07 a Monday (moderation-only).
        var request = new GenerateRosterRequest(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 7));
        when(prayerSessionRepository.existsByDateBetween(any(), any())).thenReturn(false);
        when(weeklyPrayerConfigurationRepository.findAllWithDaysOrderByEffectiveFromDesc()).thenReturn(
            List.of(configVersion(LocalDate.of(2026, 1, 1), null, Set.of(DayOfWeek.SUNDAY)))
        );

        RosterDTO result = service.generate(request);

        assertThat(result.status()).isEqualTo(RosterStatus.PUBLISHED);
        assertThat(result.periodFrom()).isEqualTo(request.from());
        assertThat(result.periodTo()).isEqualTo(request.to());

        ArgumentCaptor<PrayerSession> captor = ArgumentCaptor.forClass(PrayerSession.class);
        verify(prayerSessionRepository, times(2)).save(captor.capture());
        List<PrayerSession> sessions = captor.getAllValues();

        PrayerSession sunday = sessions.stream().filter(s -> s.getDate().getDayOfWeek() == DayOfWeek.SUNDAY).findFirst().orElseThrow();
        assertThat(sunday.isRequiresPreacher()).isTrue();
        assertThat(sunday.getAssignments()).extracting(PrayerAssignment::getRole).containsExactlyInAnyOrder(
            PrayerAssignmentRole.MODERATOR,
            PrayerAssignmentRole.PREACHER
        );
        sunday.getAssignments().forEach(a -> assertThat(a.getUser()).isNotNull());

        PrayerSession monday = sessions.stream().filter(s -> s.getDate().getDayOfWeek() == DayOfWeek.MONDAY).findFirst().orElseThrow();
        assertThat(monday.isRequiresPreacher()).isFalse();
        assertThat(monday.getAssignments()).extracting(PrayerAssignment::getRole).containsExactly(PrayerAssignmentRole.MODERATOR);
    }

    @Test
    void generate_picksTheConfigVersionThatCoversEachDateWhenTheConfigChangedMidPeriod() {
        stubSaves();
        stubFeasibleSolve();
        // Old version (moderation-only Wednesdays) covers 9/1-9/8; new version (Wednesday preaching) from 9/9.
        var oldVersion = configVersion(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 8), Set.of());
        var newVersion = configVersion(LocalDate.of(2026, 9, 9), null, Set.of(DayOfWeek.WEDNESDAY));
        var request = new GenerateRosterRequest(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 9));
        when(prayerSessionRepository.existsByDateBetween(any(), any())).thenReturn(false);
        when(weeklyPrayerConfigurationRepository.findAllWithDaysOrderByEffectiveFromDesc()).thenReturn(List.of(newVersion, oldVersion));

        service.generate(request);

        ArgumentCaptor<PrayerSession> captor = ArgumentCaptor.forClass(PrayerSession.class);
        verify(prayerSessionRepository, times(8)).save(captor.capture());
        PrayerSession sept2Wednesday = captor
            .getAllValues()
            .stream()
            .filter(s -> s.getDate().equals(LocalDate.of(2026, 9, 2)))
            .findFirst()
            .orElseThrow();
        PrayerSession sept9Wednesday = captor
            .getAllValues()
            .stream()
            .filter(s -> s.getDate().equals(LocalDate.of(2026, 9, 9)))
            .findFirst()
            .orElseThrow();
        assertThat(sept2Wednesday.isRequiresPreacher()).isFalse();
        assertThat(sept9Wednesday.isRequiresPreacher()).isTrue();
    }

    @Test
    void generate_rejectsWhenDateFallsAfterTheOnlyVersionsClosedRange() {
        // Version closed on 8/31, queried date is after that with no newer version to cover it.
        var closedVersion = configVersion(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31), Set.of());
        var request = new GenerateRosterRequest(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));
        when(prayerSessionRepository.existsByDateBetween(any(), any())).thenReturn(false);
        when(weeklyPrayerConfigurationRepository.findAllWithDaysOrderByEffectiveFromDesc()).thenReturn(List.of(closedVersion));

        assertThatThrownBy(() -> service.generate(request)).isInstanceOf(BadRequestAlertException.class);
        verify(rosterRepository, never()).save(any());
    }

    @Test
    void generate_rejectsWhenApplicableVersionIsMissingADaySetting() {
        var incompleteVersion = configVersion(LocalDate.of(2026, 1, 1), null, Set.of());
        incompleteVersion.getDays().removeIf(day -> day.getDayOfWeek() == DayOfWeek.SUNDAY);
        var request = new GenerateRosterRequest(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 6));
        when(prayerSessionRepository.existsByDateBetween(any(), any())).thenReturn(false);
        when(weeklyPrayerConfigurationRepository.findAllWithDaysOrderByEffectiveFromDesc()).thenReturn(List.of(incompleteVersion));

        assertThatThrownBy(() -> service.generate(request)).isInstanceOf(BadRequestAlertException.class);
        verify(rosterRepository, never()).save(any());
    }

    @Test
    void generate_staysInDraftAndRecordsDiagnosticWhenSolveIsInfeasible() {
        stubSaves();
        when(solverService.solve(any(), any())).thenAnswer(inv -> {
            RosterSolution problem = inv.getArgument(1);
            problem.setScore(HardSoftScore.of(-1, 0));
            return problem;
        });
        when(solverService.explainHardConstraintViolations(any())).thenReturn("everyAssignmentMustBeFilled: 1 violation(s)");
        var request = new GenerateRosterRequest(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 6));
        when(prayerSessionRepository.existsByDateBetween(any(), any())).thenReturn(false);
        when(weeklyPrayerConfigurationRepository.findAllWithDaysOrderByEffectiveFromDesc()).thenReturn(
            List.of(configVersion(LocalDate.of(2026, 1, 1), null, Set.of(DayOfWeek.SUNDAY)))
        );

        RosterDTO result = service.generate(request);

        assertThat(result.status()).isEqualTo(RosterStatus.DRAFT);
        assertThat(result.publishedAt()).isNull();

        ArgumentCaptor<RosterGeneration> captor = ArgumentCaptor.forClass(RosterGeneration.class);
        verify(rosterGenerationRepository, times(2)).save(captor.capture());
        RosterGeneration finalGeneration = captor.getAllValues().get(1);
        assertThat(finalGeneration.getStatus()).isEqualTo(RosterGenerationStatus.INFEASIBLE);
        assertThat(finalGeneration.getFeasible()).isFalse();
        assertThat(finalGeneration.getErrorMessage()).isEqualTo("everyAssignmentMustBeFilled: 1 violation(s)");
        verify(rosterRepository, never()).save(argThat(r -> r.getStatus() == RosterStatus.PUBLISHED));
    }

    @Test
    void generate_skipsSolvingAndStaysInDraftWhenNoEligibleUserExists() {
        stubSaves();
        when(userRepository.findAllEligibleActive()).thenReturn(List.of());
        var request = new GenerateRosterRequest(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 6));
        when(prayerSessionRepository.existsByDateBetween(any(), any())).thenReturn(false);
        when(weeklyPrayerConfigurationRepository.findAllWithDaysOrderByEffectiveFromDesc()).thenReturn(
            List.of(configVersion(LocalDate.of(2026, 1, 1), null, Set.of(DayOfWeek.SUNDAY)))
        );

        RosterDTO result = service.generate(request);

        assertThat(result.status()).isEqualTo(RosterStatus.DRAFT);
        verify(solverService, never()).solve(any(), any());

        ArgumentCaptor<RosterGeneration> captor = ArgumentCaptor.forClass(RosterGeneration.class);
        verify(rosterGenerationRepository, times(2)).save(captor.capture());
        RosterGeneration finalGeneration = captor.getAllValues().get(1);
        assertThat(finalGeneration.getStatus()).isEqualTo(RosterGenerationStatus.INFEASIBLE);
        assertThat(finalGeneration.getHardScore()).isEqualTo(-2);
        assertThat(finalGeneration.getErrorMessage()).contains("No active user has any capability");
    }
}
