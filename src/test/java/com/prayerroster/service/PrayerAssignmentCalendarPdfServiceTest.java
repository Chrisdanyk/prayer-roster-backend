package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.PrayerAssignmentRole;
import com.prayerroster.service.dto.UpcomingAssignmentDTO;
import com.prayerroster.service.pdf.AssignmentPdfRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.context.Context;

@ExtendWith(MockitoExtension.class)
class PrayerAssignmentCalendarPdfServiceTest {

    @Mock
    private PrayerAssignmentService prayerAssignmentService;

    @Mock
    private PdfRenderingService pdfRenderingService;

    private final ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();

    {
        messageSource.setBasename("i18n/messages");
        messageSource.setDefaultEncoding("UTF-8");
    }

    private PrayerAssignmentCalendarPdfService service;

    @BeforeEach
    void setUp() {
        service = new PrayerAssignmentCalendarPdfService(prayerAssignmentService, pdfRenderingService, messageSource);
    }

    @Test
    void renderOwnAssignmentsPdf_buildsOneRowPerAssignmentWithLocalizedRole() {
        when(prayerAssignmentService.findOwnUpcoming("u1")).thenReturn(
            List.of(
                new UpcomingAssignmentDTO(1L, LocalDate.of(2026, 9, 6), PrayerAssignmentRole.MODERATOR),
                new UpcomingAssignmentDTO(2L, LocalDate.of(2026, 9, 13), PrayerAssignmentRole.PREACHER)
            )
        );
        when(pdfRenderingService.render(any(), any())).thenReturn(new byte[] { 9 });

        byte[] result = service.renderOwnAssignmentsPdf("u1", Locale.FRENCH);

        assertThat(result).containsExactly(9);
        ArgumentCaptor<Context> captor = ArgumentCaptor.forClass(Context.class);
        verify(pdfRenderingService).render(eq("pdf/assignments"), captor.capture());
        @SuppressWarnings("unchecked")
        List<AssignmentPdfRow> rows = (List<AssignmentPdfRow>) captor.getValue().getVariable("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).roleLabel()).isEqualTo("modérateur");
        assertThat(rows.get(1).roleLabel()).isEqualTo("prédicateur");
    }

    @Test
    void renderOwnAssignmentsPdf_handlesNoUpcomingAssignments() {
        when(prayerAssignmentService.findOwnUpcoming("u1")).thenReturn(List.of());
        when(pdfRenderingService.render(any(), any())).thenReturn(new byte[0]);

        service.renderOwnAssignmentsPdf("u1", Locale.FRENCH);

        ArgumentCaptor<Context> captor = ArgumentCaptor.forClass(Context.class);
        verify(pdfRenderingService).render(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<AssignmentPdfRow> rows = (List<AssignmentPdfRow>) captor.getValue().getVariable("rows");
        assertThat(rows).isEmpty();
    }
}
