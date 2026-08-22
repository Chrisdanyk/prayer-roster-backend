package com.prayerroster.service;

import java.time.LocalDate;

/** Published after a user's unavailability period is created or edited - see {@link ReschedulingDetectionListener}. */
public record UserAvailabilityChangedEvent(String userId, LocalDate startDate, LocalDate endDate) {}
