package com.prayerroster.service;

import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReschedulingDetectionListenerTest {

    @Mock
    private ReschedulingDetectionService reschedulingDetectionService;

    private ReschedulingDetectionListener listener;

    @BeforeEach
    void setUp() {
        listener = new ReschedulingDetectionListener(reschedulingDetectionService);
    }

    @Test
    void onAvailabilityChanged_delegatesToDetectionService() {
        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = LocalDate.now().plusDays(5);

        listener.onAvailabilityChanged(new UserAvailabilityChangedEvent("u1", start, end));

        verify(reschedulingDetectionService).onAvailabilityCreated("u1", start, end);
    }

    @Test
    void onUserDeactivated_delegatesToDetectionService() {
        listener.onUserDeactivated(new UserDeactivatedEvent("u1"));

        verify(reschedulingDetectionService).onUserDeactivated("u1");
    }
}
