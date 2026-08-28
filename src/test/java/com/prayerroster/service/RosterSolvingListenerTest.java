package com.prayerroster.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RosterSolvingListenerTest {

    @Mock
    private RosterSolvingService rosterSolvingService;

    private RosterSolvingListener listener;

    @BeforeEach
    void setUp() {
        listener = new RosterSolvingListener(rosterSolvingService);
    }

    @Test
    void onRosterGenerationRequested_delegatesToRosterSolvingService() {
        listener.onRosterGenerationRequested(new RosterGenerationRequestedEvent(42L));

        verify(rosterSolvingService).solveAndApply(42L);
    }

    @Test
    void onRosterGenerationRequested_marksTheGenerationFailedWhenTheSolveThrows() {
        doThrow(new RuntimeException("Timefold blew up")).when(rosterSolvingService).solveAndApply(42L);

        listener.onRosterGenerationRequested(new RosterGenerationRequestedEvent(42L));

        verify(rosterSolvingService).markFailed(eq(42L), anyString());
    }

    @Test
    void onRosterGenerationRequested_fallsBackToTheExceptionClassNameWhenTheMessageIsNull() {
        doThrow(new RuntimeException()).when(rosterSolvingService).solveAndApply(42L);

        listener.onRosterGenerationRequested(new RosterGenerationRequestedEvent(42L));

        verify(rosterSolvingService).markFailed(eq(42L), eq("RuntimeException"));
    }
}
