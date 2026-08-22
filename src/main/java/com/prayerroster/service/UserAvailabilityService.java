package com.prayerroster.service;

import com.prayerroster.domain.User;
import com.prayerroster.domain.UserAvailability;
import com.prayerroster.domain.UserAvailabilityStatus;
import com.prayerroster.repository.UserAvailabilityRepository;
import com.prayerroster.repository.UserRepository;
import com.prayerroster.service.dto.AvailabilityRequest;
import com.prayerroster.service.dto.UserAvailabilityDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service unavailability management - every method is scoped to the calling user's own id,
 * enforced by the repository query itself (findByIdAndUserId), not by a separate authorization
 * check, so there is no path that can leak or mutate another user's record.
 * <p>
 * "Cancel" is a soft status change (ACTIVE -&gt; CANCELLED), never a hard delete, so historical
 * unavailability stays auditable - see docs/phase1-architecture.md section 2.
 */
@Service
@Transactional
public class UserAvailabilityService {

    private static final String ENTITY_NAME = "userAvailability";

    private final UserAvailabilityRepository availabilityRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public UserAvailabilityService(
        UserAvailabilityRepository availabilityRepository,
        UserRepository userRepository,
        ApplicationEventPublisher eventPublisher
    ) {
        this.availabilityRepository = availabilityRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<UserAvailabilityDTO> findOwn(String userId) {
        return availabilityRepository.findByUserIdOrderByStartDateDesc(userId).stream().map(UserAvailabilityDTO::from).toList();
    }

    public UserAvailabilityDTO create(String userId, AvailabilityRequest request) {
        validateRange(request);
        validateNoOverlap(userId, request, null);

        User user = userRepository.getReferenceById(userId);
        UserAvailability availability = new UserAvailability();
        availability.setUser(user);
        availability.setStartDate(request.startDate());
        availability.setEndDate(request.endDate());
        availability.setReason(request.reason());
        availability.setStatus(UserAvailabilityStatus.ACTIVE);
        UserAvailabilityDTO saved = UserAvailabilityDTO.from(availabilityRepository.save(availability));
        eventPublisher.publishEvent(new UserAvailabilityChangedEvent(userId, request.startDate(), request.endDate()));
        return saved;
    }

    public UserAvailabilityDTO update(String userId, Long id, AvailabilityRequest request) {
        UserAvailability existing = getOwned(userId, id);
        requireNotFullyPast(existing);
        validateRange(request);
        validateNoOverlap(userId, request, id);

        existing.setStartDate(request.startDate());
        existing.setEndDate(request.endDate());
        existing.setReason(request.reason());
        UserAvailabilityDTO saved = UserAvailabilityDTO.from(availabilityRepository.save(existing));
        eventPublisher.publishEvent(new UserAvailabilityChangedEvent(userId, request.startDate(), request.endDate()));
        return saved;
    }

    public void cancel(String userId, Long id) {
        UserAvailability existing = getOwned(userId, id);
        requireNotFullyPast(existing);
        existing.setStatus(UserAvailabilityStatus.CANCELLED);
        availabilityRepository.save(existing);
    }

    private UserAvailability getOwned(String userId, Long id) {
        return availabilityRepository.findByIdAndUserId(id, userId).orElseThrow(() -> new EntityNotFoundException(
            "Availability not found: " + id
        ));
    }

    private void requireNotFullyPast(UserAvailability existing) {
        if (existing.getEndDate().isBefore(LocalDate.now())) {
            throw new BadRequestAlertException("Cannot modify a fully past availability record", ENTITY_NAME, "immutablePastRecord");
        }
    }

    private void validateRange(AvailabilityRequest request) {
        if (request.startDate().isAfter(request.endDate())) {
            throw new BadRequestAlertException("startDate must not be after endDate", ENTITY_NAME, "invalidDateRange");
        }
    }

    private void validateNoOverlap(String userId, AvailabilityRequest request, Long excludeId) {
        List<UserAvailability> overlapping = availabilityRepository.findOverlapping(
            userId,
            request.startDate(),
            request.endDate(),
            excludeId
        );
        if (!overlapping.isEmpty()) {
            throw new BadRequestAlertException("Overlaps an existing unavailability period", ENTITY_NAME, "overlappingPeriod");
        }
    }
}
