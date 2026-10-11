package com.aether.client.core;

/**
 * The connection states the UI renders, and the transitions between them.
 *
 * <p>States are published by the service as they actually happen. A connect the user
 * requested but the system refused, a core that failed to bind, and a tunnel that came up
 * and then died are all different failures and the UI has to be able to say so.
 */
public enum ConnectionState {

    /** Not connected, nothing running. The button offers to connect. */
    DISCONNECTED("disconnected"),

    /** Process spawned, waiting for the SOCKS listener. */
    STARTING("starting"),

    /** The listener is up; the transport handshake is in progress. */
    SCANNING("scanning"),

    /** A gateway validated and the tunnel is being secured. */
    SECURING("securing"),

    /** Traffic is confirmed flowing through the tunnel. */
    CONNECTED("connected"),

    /** Going down on request. */
    DISCONNECTING("disconnecting"),

    /** Something failed; the message explains what. */
    ERROR("error");

    public final String id;

    ConnectionState(String id) {
        this.id = id;
    }

    /** True while the tunnel is carrying traffic. */
    public boolean isUp() {
        return this == CONNECTED;
    }

    /** True while a session exists, whether or not it is carrying traffic yet. */
    public boolean isBusy() {
        return this == STARTING || this == SCANNING || this == SECURING || this == DISCONNECTING;
    }

    public static ConnectionState fromId(String id) {
        for (ConnectionState state : values()) {
            if (state.id.equals(id)) return state;
        }
        return DISCONNECTED;
    }
}