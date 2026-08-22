package com.prayerroster.service;

import com.prayerroster.service.dto.UpcomingAssignmentDTO;
import com.prayerroster.service.pdf.AssignmentPdfRow;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;

/**
 * Renders one user's own upcoming assignments to a personal calendar PDF (see
 * docs/phase1-architecture.md section 13) - reuses {@link PrayerAssignmentService} so the
 * "upcoming" window is defined in exactly one place, shared with the JSON listing endpoint.
 */
@Service
public class PrayerAssignmentCalendarPdfService {

    private final PrayerAssignmentService prayerAssignmentService;
    private final PdfRenderingService pdfRenderingService;
    private final MessageSource messageSource;

    public PrayerAssignmentCalendarPdfService(
        PrayerAssignmentService prayerAssignmentService,
        PdfRenderingService pdfRenderingService,
        MessageSource messageSource
    ) {
        this.prayerAssignmentService = prayerAssignmentService;
        this.pdfRenderingService = pdfRenderingService;
        this.messageSource = messageSource;
    }

    public byte[] renderOwnAssignmentsPdf(String userId, Locale locale) {
        List<UpcomingAssignmentDTO> assignments = prayerAssignmentService.findOwnUpcoming(userId);
        List<AssignmentPdfRow> rows = assignments.stream().map(assignment -> toRow(assignment, locale)).toList();

        Context context = new Context();
        context.setVariable("title", messageSource.getMessage("pdf.assignments.title", null, locale));
        context.setVariable("rows", rows);
        return pdfRenderingService.render("pdf/assignments", context);
    }

    private AssignmentPdfRow toRow(UpcomingAssignmentDTO assignment, Locale locale) {
        String dateLabel = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(assignment.date());
        String roleLabel = messageSource.getMessage("role." + assignment.role().name(), null, locale);
        return new AssignmentPdfRow(dateLabel, roleLabel);
    }
}
