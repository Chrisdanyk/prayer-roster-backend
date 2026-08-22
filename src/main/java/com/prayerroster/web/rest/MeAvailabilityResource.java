package com.prayerroster.web.rest;

import com.prayerroster.security.SecurityUtils;
import com.prayerroster.service.UserAvailabilityService;
import com.prayerroster.service.dto.AvailabilityRequest;
import com.prayerroster.service.dto.UserAvailabilityDTO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Self-service unavailability management. Deliberately not permission-gated beyond authentication:
 * every authenticated user manages only their own data here, scoped by the JWT's sub claim, not by
 * a business permission - see docs/phase1-architecture.md's authorization model.
 */
@RestController
@RequestMapping("/api/me/availability")
public class MeAvailabilityResource {

    private final UserAvailabilityService availabilityService;

    public MeAvailabilityResource(UserAvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping
    public List<UserAvailabilityDTO> getOwnAvailability() {
        return availabilityService.findOwn(currentUserId());
    }

    @PostMapping
    public ResponseEntity<UserAvailabilityDTO> createAvailability(@Valid @RequestBody AvailabilityRequest request) {
        UserAvailabilityDTO created = availabilityService.create(currentUserId(), request);
        return ResponseEntity.status(201).body(created);
    }

    @PutMapping("/{id}")
    public UserAvailabilityDTO updateAvailability(@PathVariable Long id, @Valid @RequestBody AvailabilityRequest request) {
        return availabilityService.update(currentUserId(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelAvailability(@PathVariable Long id) {
        availabilityService.cancel(currentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    private String currentUserId() {
        return SecurityUtils.getCurrentUserLogin().orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }
}
