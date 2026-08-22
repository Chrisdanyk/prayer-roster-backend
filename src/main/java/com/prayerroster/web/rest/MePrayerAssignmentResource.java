package com.prayerroster.web.rest;

import com.prayerroster.repository.UserRepository;
import com.prayerroster.security.SecurityUtils;
import com.prayerroster.service.PrayerAssignmentCalendarPdfService;
import com.prayerroster.service.PrayerAssignmentService;
import com.prayerroster.service.dto.UpcomingAssignmentDTO;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service view of a user's own upcoming prayer assignments. Deliberately not permission-gated
 * beyond authentication: every authenticated user reads only their own data here, scoped by the
 * JWT's sub claim, not by a business permission - see docs/phase1-architecture.md's authorization
 * model.
 */
@RestController
@RequestMapping("/api/me/prayer-assignments")
public class MePrayerAssignmentResource {

    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("fr");

    private final PrayerAssignmentService prayerAssignmentService;
    private final PrayerAssignmentCalendarPdfService prayerAssignmentCalendarPdfService;
    private final UserRepository userRepository;

    public MePrayerAssignmentResource(
        PrayerAssignmentService prayerAssignmentService,
        PrayerAssignmentCalendarPdfService prayerAssignmentCalendarPdfService,
        UserRepository userRepository
    ) {
        this.prayerAssignmentService = prayerAssignmentService;
        this.prayerAssignmentCalendarPdfService = prayerAssignmentCalendarPdfService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<UpcomingAssignmentDTO> getOwnUpcomingAssignments() {
        return prayerAssignmentService.findOwnUpcoming(currentUserId());
    }

    @GetMapping("/pdf")
    public ResponseEntity<byte[]> getOwnUpcomingAssignmentsPdf() {
        String userId = currentUserId();
        byte[] pdf = prayerAssignmentCalendarPdfService.renderOwnAssignmentsPdf(userId, currentLocale(userId));
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"mes-affectations.pdf\"")
            .body(pdf);
    }

    private String currentUserId() {
        return SecurityUtils.getCurrentUserLogin().orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }

    private Locale currentLocale(String userId) {
        return userRepository.findById(userId).map(user -> Locale.forLanguageTag(user.getLangKey())).orElse(DEFAULT_LOCALE);
    }
}
