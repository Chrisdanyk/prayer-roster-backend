package com.prayerroster.web.rest;

import com.prayerroster.repository.UserRepository;
import com.prayerroster.security.SecurityUtils;
import com.prayerroster.service.ReschedulingService;
import com.prayerroster.service.RosterGenerationService;
import com.prayerroster.service.RosterPdfService;
import com.prayerroster.service.RosterService;
import com.prayerroster.service.dto.GenerateRosterRequest;
import com.prayerroster.service.dto.RescheduleRequest;
import com.prayerroster.service.dto.RosterDTO;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tech.jhipster.web.util.PaginationUtil;

@RestController
@RequestMapping("/api/rosters")
public class RosterResource {

    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("fr");

    private final RosterGenerationService rosterGenerationService;
    private final RosterService rosterService;
    private final ReschedulingService reschedulingService;
    private final RosterPdfService rosterPdfService;
    private final UserRepository userRepository;

    public RosterResource(
        RosterGenerationService rosterGenerationService,
        RosterService rosterService,
        ReschedulingService reschedulingService,
        RosterPdfService rosterPdfService,
        UserRepository userRepository
    ) {
        this.rosterGenerationService = rosterGenerationService;
        this.rosterService = rosterService;
        this.reschedulingService = reschedulingService;
        this.rosterPdfService = rosterPdfService;
        this.userRepository = userRepository;
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('PERM_ROSTER_GENERATE')")
    public ResponseEntity<RosterDTO> generate(@Valid @RequestBody GenerateRosterRequest request) {
        RosterDTO created = rosterGenerationService.generate(request);
        return ResponseEntity.status(201).body(created);
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasAuthority('PERM_ROSTER_RESCHEDULE')")
    public RosterDTO reschedule(@PathVariable Long id, @Valid @RequestBody(required = false) RescheduleRequest request) {
        return reschedulingService.reschedule(id, request != null ? request.reason() : null);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_ROSTER_VIEW')")
    public ResponseEntity<List<RosterDTO>> getRosters(@PageableDefault(size = 20) Pageable pageable) {
        Page<RosterDTO> page = rosterService.findAll(pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(ServletUriComponentsBuilder.fromCurrentRequest(), page);
        return ResponseEntity.ok().headers(headers).body(page.getContent());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_ROSTER_VIEW')")
    public RosterDTO getRoster(@PathVariable Long id) {
        return rosterService.findOne(id);
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAuthority('PERM_ROSTER_VIEW')")
    public ResponseEntity<byte[]> getRosterPdf(@PathVariable Long id) {
        byte[] pdf = rosterPdfService.renderRosterPdf(id, currentLocale());
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"roster-" + id + ".pdf\"")
            .body(pdf);
    }

    private Locale currentLocale() {
        return SecurityUtils.getCurrentUserLogin()
            .flatMap(userRepository::findById)
            .map(user -> Locale.forLanguageTag(user.getLangKey()))
            .orElse(DEFAULT_LOCALE);
    }
}
