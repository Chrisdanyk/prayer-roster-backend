package com.prayerroster.web.rest;

import com.prayerroster.service.UserAvailabilityService;
import com.prayerroster.service.dto.AvailabilityRequest;
import com.prayerroster.service.dto.UserAvailabilityDTO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Lets an administrator record an absence on a member's behalf - somebody phones in rather than
 * using the app. Delegates to the same service the self-service path uses: recording an absence
 * ({@code POST}) publishes the same {@code UserAvailabilityChangedEvent} the self-service path
 * publishes, so rescheduling still triggers - recording one without that event would leave the
 * roster holding an assignment that cannot be served. Cancelling ({@code DELETE}) publishes no
 * event, on this path or the self-service one, since {@link UserAvailabilityService#cancel} does
 * not publish one.
 */
@RestController
@RequestMapping("/api/users/{userId}/availability")
public class AdminUserAvailabilityResource {

    private final UserAvailabilityService userAvailabilityService;

    public AdminUserAvailabilityResource(UserAvailabilityService userAvailabilityService) {
        this.userAvailabilityService = userAvailabilityService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_AVAILABILITY_VIEW')")
    public List<UserAvailabilityDTO> getForUser(@PathVariable String userId) {
        return userAvailabilityService.findOwn(userId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_AVAILABILITY_MANAGE')")
    public ResponseEntity<UserAvailabilityDTO> createForUser(@PathVariable String userId, @Valid @RequestBody AvailabilityRequest request) {
        return ResponseEntity.status(201).body(userAvailabilityService.create(userId, request));
    }

    @DeleteMapping("/{availabilityId}")
    @PreAuthorize("hasAuthority('PERM_AVAILABILITY_MANAGE')")
    public ResponseEntity<Void> cancelForUser(@PathVariable String userId, @PathVariable Long availabilityId) {
        userAvailabilityService.cancel(userId, availabilityId);
        return ResponseEntity.noContent().build();
    }
}
