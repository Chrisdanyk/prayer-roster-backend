package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.domain.Roster;
import com.prayerroster.domain.User;
import com.prayerroster.repository.PrayerSessionRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.service.pdf.RosterPdfWeek;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.context.Context;

@ExtendWith(MockitoExtension.class)
class RosterPdfServiceTest {

    @Mock
    private RosterRepository rosterRepository;

    @Mock
    private PrayerSessionRepository prayerSessionRepository;

    @Mock
    private PdfRenderingService pdfRenderingService;

    private final ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();

    {
        messageSource.setBasename("i18n/messages");
        messageSource.setDefaultEncoding("UTF-8");
    }

    private RosterPdfService service;

    @BeforeEach
    void setUp() {
        service = new RosterPdfService(rosterRepository, prayerSessionRepository, pdfRenderingService, messageSource);
    }

    private static User user(String id, String firstName, String lastName) {
        User user = new User();
        user.setId(id);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        return user;
    }

    private static PrayerSession session(Long id, LocalDate date, boolean requiresPreacher, PrayerAssignment... assignments) {
        PrayerSession session = new PrayerSession();
        session.setId(id);
        session.setDate(date);
        session.setDayOfWeek(date.getDayOfWeek());
        session.setRequiresPreacher(requiresPreacher);
        session.setAssignments(Set.of(assignments));
        return session;
    }

    private static PrayerAssignment assignment(PrayerAssignmentRole role, User user) {
        PrayerAssignment assignment = new PrayerAssignment();
        assignment.setRole(role);
        assignment.setUser(user);
        return assignment;
    }

    @Test
    void renderRosterPdf_throwsWhenRosterNotFound() {
        when(rosterRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renderRosterPdf(99L, Locale.FRENCH)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void renderRosterPdf_groupsSessionsIntoWeeksStartingMonday() {
        Roster roster = new Roster();
        roster.setId(1L);
        roster.setPeriodFrom(LocalDate.of(2026, 9, 1));
        roster.setPeriodTo(LocalDate.of(2026, 9, 14));
        when(rosterRepository.findById(1L)).thenReturn(Optional.of(roster));

        User moderator = user("u1", "Jean", "Dupont");
        PrayerSession sundaySameWeek = session(1L, LocalDate.of(2026, 9, 6), false, assignment(PrayerAssignmentRole.MODERATOR, moderator));
        PrayerSession sundayNextWeek = session(2L, LocalDate.of(2026, 9, 13), false, assignment(PrayerAssignmentRole.MODERATOR, moderator));
        when(prayerSessionRepository.findByRosterIdWithAssignments(1L)).thenReturn(List.of(sundaySameWeek, sundayNextWeek));

        service.renderRosterPdf(1L, Locale.FRENCH);

        ArgumentCaptor<Context> captor = ArgumentCaptor.forClass(Context.class);
        verify(pdfRenderingService).render(eq("pdf/roster"), captor.capture());
        @SuppressWarnings("unchecked")
        List<RosterPdfWeek> weeks = (List<RosterPdfWeek>) captor.getValue().getVariable("weeks");
        assertThat(weeks).hasSize(2);
        assertThat(weeks.get(0).rows()).hasSize(1);
        assertThat(weeks.get(1).rows()).hasSize(1);
    }

    @Test
    void renderRosterPdf_marksUnfilledModeratorAsVacant() {
        Roster roster = new Roster();
        roster.setId(1L);
        roster.setPeriodFrom(LocalDate.of(2026, 9, 1));
        roster.setPeriodTo(LocalDate.of(2026, 9, 1));
        when(rosterRepository.findById(1L)).thenReturn(Optional.of(roster));

        PrayerSession unfilled = session(1L, LocalDate.of(2026, 9, 6), false, assignment(PrayerAssignmentRole.MODERATOR, null));
        when(prayerSessionRepository.findByRosterIdWithAssignments(1L)).thenReturn(List.of(unfilled));

        service.renderRosterPdf(1L, Locale.FRENCH);

        ArgumentCaptor<Context> captor = ArgumentCaptor.forClass(Context.class);
        verify(pdfRenderingService).render(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<RosterPdfWeek> weeks = (List<RosterPdfWeek>) captor.getValue().getVariable("weeks");
        assertThat(weeks.get(0).rows().get(0).moderatorName()).isEqualTo("À pourvoir");
    }

    @Test
    void renderRosterPdf_leavesPreacherColumnBlankWhenNotRequired() {
        Roster roster = new Roster();
        roster.setId(1L);
        roster.setPeriodFrom(LocalDate.of(2026, 9, 1));
        roster.setPeriodTo(LocalDate.of(2026, 9, 1));
        when(rosterRepository.findById(1L)).thenReturn(Optional.of(roster));

        PrayerSession moderationOnly = session(
            1L,
            LocalDate.of(2026, 9, 6),
            false,
            assignment(PrayerAssignmentRole.MODERATOR, user("u1", "Jean", "Dupont"))
        );
        when(prayerSessionRepository.findByRosterIdWithAssignments(1L)).thenReturn(List.of(moderationOnly));

        service.renderRosterPdf(1L, Locale.FRENCH);

        ArgumentCaptor<Context> captor = ArgumentCaptor.forClass(Context.class);
        verify(pdfRenderingService).render(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<RosterPdfWeek> weeks = (List<RosterPdfWeek>) captor.getValue().getVariable("weeks");
        assertThat(weeks.get(0).rows().get(0).preacherName()).isEmpty();
    }

    @Test
    void renderRosterPdf_fillsPreacherNameWhenAssigned() {
        Roster roster = new Roster();
        roster.setId(1L);
        roster.setPeriodFrom(LocalDate.of(2026, 9, 1));
        roster.setPeriodTo(LocalDate.of(2026, 9, 1));
        when(rosterRepository.findById(1L)).thenReturn(Optional.of(roster));

        User preacher = user("u2", "Marie", "Curie");
        PrayerSession full = session(
            1L,
            LocalDate.of(2026, 9, 6),
            true,
            assignment(PrayerAssignmentRole.MODERATOR, user("u1", "Jean", "Dupont")),
            assignment(PrayerAssignmentRole.PREACHER, preacher)
        );
        when(prayerSessionRepository.findByRosterIdWithAssignments(1L)).thenReturn(List.of(full));

        service.renderRosterPdf(1L, Locale.FRENCH);

        ArgumentCaptor<Context> captor = ArgumentCaptor.forClass(Context.class);
        verify(pdfRenderingService).render(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<RosterPdfWeek> weeks = (List<RosterPdfWeek>) captor.getValue().getVariable("weeks");
        assertThat(weeks.get(0).rows().get(0).preacherName()).isEqualTo("Marie Curie");
    }

    @Test
    void renderRosterPdf_returnsRenderedBytes() {
        Roster roster = new Roster();
        roster.setId(1L);
        roster.setPeriodFrom(LocalDate.of(2026, 9, 1));
        roster.setPeriodTo(LocalDate.of(2026, 9, 1));
        when(rosterRepository.findById(1L)).thenReturn(Optional.of(roster));
        when(prayerSessionRepository.findByRosterIdWithAssignments(1L)).thenReturn(List.of());
        when(pdfRenderingService.render(any(), any())).thenReturn(new byte[] { 1, 2, 3 });

        byte[] result = service.renderRosterPdf(1L, Locale.FRENCH);

        assertThat(result).containsExactly(1, 2, 3);
    }
}
