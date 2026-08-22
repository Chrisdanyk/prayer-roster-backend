package com.prayerroster.web.rest;

import com.prayerroster.service.RosterGenerationService;
import com.prayerroster.service.RosterService;
import com.prayerroster.service.dto.GenerateRosterRequest;
import com.prayerroster.service.dto.RosterDTO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tech.jhipster.web.util.PaginationUtil;

@RestController
@RequestMapping("/api/rosters")
public class RosterResource {

    private final RosterGenerationService rosterGenerationService;
    private final RosterService rosterService;

    public RosterResource(RosterGenerationService rosterGenerationService, RosterService rosterService) {
        this.rosterGenerationService = rosterGenerationService;
        this.rosterService = rosterService;
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('PERM_ROSTER_GENERATE')")
    public ResponseEntity<RosterDTO> generate(@Valid @RequestBody GenerateRosterRequest request) {
        RosterDTO created = rosterGenerationService.generate(request);
        return ResponseEntity.status(201).body(created);
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
}
