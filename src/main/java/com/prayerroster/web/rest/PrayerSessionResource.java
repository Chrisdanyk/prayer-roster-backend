package com.prayerroster.web.rest;

import com.prayerroster.service.PrayerSessionService;
import com.prayerroster.service.dto.PrayerSessionDTO;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/prayer-sessions")
public class PrayerSessionResource {

    private final PrayerSessionService prayerSessionService;

    public PrayerSessionResource(PrayerSessionService prayerSessionService) {
        this.prayerSessionService = prayerSessionService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_ROSTER_VIEW')")
    public List<PrayerSessionDTO> getSessions(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return prayerSessionService.findByDateRange(from, to);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_ROSTER_VIEW')")
    public PrayerSessionDTO getSession(@PathVariable Long id) {
        return prayerSessionService.findOne(id);
    }
}
