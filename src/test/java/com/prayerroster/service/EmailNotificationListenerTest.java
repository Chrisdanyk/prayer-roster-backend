package com.prayerroster.service;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailNotificationListenerTest {

    @Mock
    private EmailNotificationService emailNotificationService;

    private EmailNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new EmailNotificationListener(emailNotificationService);
    }

    @Test
    void onNotificationCreated_delegatesToEmailNotificationService() {
        listener.onNotificationCreated(new NotificationCreatedEvent(42L));

        verify(emailNotificationService).sendForNotification(42L);
    }
}
