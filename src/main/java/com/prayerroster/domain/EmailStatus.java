package com.prayerroster.domain;

public enum EmailStatus {
    PENDING,
    SENT,
    FAILED,
    /** The recipient has {@code NotificationPreference.emailEnabled == false} - deliberately not sent. */
    SKIPPED,
}
