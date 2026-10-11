package com.aether.client.core;

/**
 * The settings {@link CoreEnvironment} reads.
 *
 * <p>Narrower than {@link Settings} on purpose. Building the core's environment is the
 * part of this app where a mistake is invisible -- the core starts, applies its own
 * defaults, and the app reports a setting as applied when it was not -- so the logic is
 * worth testing. Reading it through {@code SharedPreferences} would put that logic out of
 * reach of a JVM unit test, which is the only kind of test that runs in CI without a
 * device. This interface is the seam that makes it reachable.
 */
public interface ConfigSource {

    String protocol();

    String scanMode();

    String logLevel();

    String ipMode();

    String obfuscation();

    String masqueTransport();

    String goolMode();

    String psiphonTransport();

    String psiphonRegion();

    boolean quicV2();

    String peer();

    String ech();

    boolean gateway();

    boolean noDataCheck();

    /**
     * Whether to split the HTTP/2 handshake across fragments.
     *
     * <p>Aimed at middleboxes that drop a recognisable initial packet, so it is only ever
     * worth enabling on a network where plain MASQUE already fails.
     */
    boolean h2Fragment();

    String h2FragmentSize();

    String h2FragmentDelay();

    boolean routeSniff();

    int routeSniffMs();

    int tunMtu();

    int validateSecs();

    int startupSecs();

    int reconnectSecs();

    int wgKeepalive();

    boolean wgNoProfileRetry();

    String mimOuterPeer();

    String mimInnerPeer();

    String wiwOuterPeer();

    String wiwInnerPeer();

    String upstreamProxy();

    boolean quickReconnect();
}