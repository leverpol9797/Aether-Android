package com.aether.client.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ConnectionState} translation.
 *
 * <p>The state crosses a broadcast as a string, so this mapping is what stands between a
 * running tunnel and a dashboard that disagrees with it. An id that fails to parse must
 * resolve to a state rather than throw inside a broadcast receiver, because an exception
 * there is swallowed by the framework and the UI silently stops updating -- which looks
 * exactly like a frozen tunnel.
 */
public class ConnectionStateTest {

    @Test
    public void everyStateRoundTripsThroughItsId() {
        for (ConnectionState state : ConnectionState.values()) {
            assertEquals(state, ConnectionState.fromId(state.id));
        }
    }

    @Test
    public void anUnknownIdResolvesToDisconnected() {
        // The safe direction: showing "disconnected" prompts a tap, whereas a guessed
        // "connected" tells the user they are protected while nothing is running.
        assertEquals(ConnectionState.DISCONNECTED, ConnectionState.fromId("nonsense"));
        assertEquals(ConnectionState.DISCONNECTED, ConnectionState.fromId(null));
        assertEquals(ConnectionState.DISCONNECTED, ConnectionState.fromId(""));
    }

    @Test
    public void upMeansConnectedAndNothingElse() {
        assertTrue(ConnectionState.CONNECTED.isUp());
        for (ConnectionState state : ConnectionState.values()) {
            if (state != ConnectionState.CONNECTED) {
                assertFalse(state.id + " should not count as up", state.isUp());
            }
        }
    }

    @Test
    public void busyCoversEveryIntermediateStep() {
        // The connect button is a toggle. A state that is neither up nor busy would make
        // the button do nothing at that moment.
        for (ConnectionState state : ConnectionState.values()) {
            if (state == ConnectionState.DISCONNECTED || state == ConnectionState.CONNECTED
                    || state == ConnectionState.ERROR) {
                assertFalse(state.id, state.isBusy());
            } else {
                assertTrue(state.id + " should be busy", state.isBusy());
            }
        }
    }

    @Test
    public void noStateIsBothUpAndBusy() {
        for (ConnectionState state : ConnectionState.values()) {
            assertFalse(state.id, state.isUp() && state.isBusy());
        }
    }
}