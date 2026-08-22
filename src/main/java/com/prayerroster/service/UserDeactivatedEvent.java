package com.prayerroster.service;

/** Published after a user transitions from active to inactive - see {@link ReschedulingDetectionListener}. */
public record UserDeactivatedEvent(String userId) {}
