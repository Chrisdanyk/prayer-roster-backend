package com.prayerroster.web.rest;

import com.prayerroster.service.AllowedEmailService;
import com.prayerroster.service.dto.AllowedEmailDTO;
import com.prayerroster.service.dto.InviteEmailRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Manages who is permitted to authenticate at all. Deleting an entry does not lock out a person who
 * has already signed in - deactivate them via {@code PUT /api/users/{id}/status} instead.
 */
@RestController
@RequestMapping("/api/allowed-emails")
public class AllowedEmailResource {

    private final AllowedEmailService allowedEmailService;

    public AllowedEmailResource(AllowedEmailService allowedEmailService) {
        this.allowedEmailService = allowedEmailService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_USER_VIEW')")
    public List<AllowedEmailDTO> getAllowedEmails() {
        return allowedEmailService.findAll();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_USER_CREATE')")
    public ResponseEntity<AllowedEmailDTO> inviteEmail(@Valid @RequestBody InviteEmailRequest request) {
        return ResponseEntity.status(201).body(allowedEmailService.invite(request.email()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_USER_DELETE')")
    public ResponseEntity<Void> deleteAllowedEmail(@PathVariable Long id) {
        allowedEmailService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resend")
    @PreAuthorize("hasAuthority('PERM_USER_CREATE')")
    public ResponseEntity<Void> resendInvitation(@PathVariable Long id) {
        allowedEmailService.resend(id);
        return ResponseEntity.noContent().build();
    }
}
