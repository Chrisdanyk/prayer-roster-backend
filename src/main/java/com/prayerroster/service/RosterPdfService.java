package com.prayerroster.service;

import com.prayerroster.domain.PrayerAssignment;
import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.domain.PrayerSession;
import com.prayerroster.domain.Roster;
import com.prayerroster.domain.User;
import com.prayerroster.repository.PrayerSessionRepository;
import com.prayerroster.repository.RosterRepository;
import com.prayerroster.service.pdf.RosterPdfRow;
import com.prayerroster.service.pdf.RosterPdfWeek;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;

/**
 * Renders a {@link Roster} to a week-grouped PDF (see docs/phase1-architecture.md section 13) -
 * unfilled slots print as a localized "vacant" placeholder rather than a blank cell, so a printed
 * roster never looks like a data-loading glitch.
 */
@Service
@Transactional(readOnly = true)
public class RosterPdfService {

    private final RosterRepository rosterRepository;
    private final PrayerSessionRepository prayerSessionRepository;
    private final PdfRenderingService pdfRenderingService;
    private final MessageSource messageSource;

    public RosterPdfService(
        RosterRepository rosterRepository,
        PrayerSessionRepository prayerSessionRepository,
        PdfRenderingService pdfRenderingService,
        MessageSource messageSource
    ) {
        this.rosterRepository = rosterRepository;
        this.prayerSessionRepository = prayerSessionRepository;
        this.pdfRenderingService = pdfRenderingService;
        this.messageSource = messageSource;
    }

    public byte[] renderRosterPdf(Long rosterId, Locale locale) {
        Roster roster = rosterRepository.findById(rosterId).orElseThrow(() -> new EntityNotFoundException("Roster not found: " + rosterId));
        List<PrayerSession> sessions = prayerSessionRepository.findByRosterIdWithAssignments(rosterId);

        Context context = new Context();
        context.setVariable("title", messageSource.getMessage("pdf.roster.title", null, locale));
        context.setVariable("periodFrom", formatDate(roster.getPeriodFrom(), locale));
        context.setVariable("periodTo", formatDate(roster.getPeriodTo(), locale));
        context.setVariable("moderatorLabel", messageSource.getMessage("role.MODERATOR", null, locale));
        context.setVariable("preacherLabel", messageSource.getMessage("role.PREACHER", null, locale));
        context.setVariable("weeks", groupByWeek(sessions, locale));
        return pdfRenderingService.render("pdf/roster", context);
    }

    private List<RosterPdfWeek> groupByWeek(List<PrayerSession> sessions, Locale locale) {
        Map<LocalDate, List<RosterPdfRow>> rowsByWeekStart = new LinkedHashMap<>();
        for (PrayerSession session : sessions) {
            LocalDate weekStart = session.getDate().with(DayOfWeek.MONDAY);
            rowsByWeekStart.computeIfAbsent(weekStart, key -> new ArrayList<>()).add(toRow(session, locale));
        }
        List<RosterPdfWeek> weeks = new ArrayList<>();
        rowsByWeekStart.forEach((weekStart, rows) ->
            weeks.add(new RosterPdfWeek(messageSource.getMessage("pdf.weekOf", new Object[] { formatDate(weekStart, locale) }, locale), rows))
        );
        return weeks;
    }

    private RosterPdfRow toRow(PrayerSession session, Locale locale) {
        String moderatorName = nameOrVacant(findAssignedUser(session, PrayerAssignmentRole.MODERATOR), locale);
        String preacherName = session.isRequiresPreacher() ? nameOrVacant(findAssignedUser(session, PrayerAssignmentRole.PREACHER), locale) : "";
        return new RosterPdfRow(
            formatDate(session.getDate(), locale),
            session.getDate().getDayOfWeek().getDisplayName(TextStyle.FULL, locale),
            moderatorName,
            preacherName
        );
    }

    private User findAssignedUser(PrayerSession session, PrayerAssignmentRole role) {
        // findFirst() before map(): Stream#findFirst throws NullPointerException if the mapped
        // element itself is null (an unfilled slot's getUser()), since it wraps results via
        // Optional.of rather than Optional.ofNullable - find the (never-null) assignment first.
        return session
            .getAssignments()
            .stream()
            .filter(assignment -> assignment.getRole() == role)
            .findFirst()
            .map(PrayerAssignment::getUser)
            .orElse(null);
    }

    private String nameOrVacant(User user, Locale locale) {
        if (user == null) {
            return messageSource.getMessage("pdf.vacant", null, locale);
        }
        return user.getFirstName() + " " + user.getLastName();
    }

    private String formatDate(LocalDate date, Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(date);
    }
}
