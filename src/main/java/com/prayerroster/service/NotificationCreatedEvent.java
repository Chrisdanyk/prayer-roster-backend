package com.prayerroster.service;

/** Published after a {@link com.prayerroster.domain.Notification} row is created - see {@link EmailNotificationListener}. */
public record NotificationCreatedEvent(Long notificationId) {}
