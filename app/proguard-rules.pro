# The core binary and the packet bridge are reached by name, from native code and from
# ProcessBuilder respectively. R8 cannot see either reference, so both rules are load-bearing.
-keep class com.aether.client.service.AetherVpnService { *; }
-keep class com.aether.client.MainActivity { *; }

# Invoked by the system through the manifest; instantiated by name.
-keep class com.aether.client.tun.TunBridge { *; }

# The JNI layer binds these by their exact names in C.
-keepclasseswithmembernames class com.aether.client.tun.HevBridge {
    native <methods>;
}

# Custom views inflated from XML by name.
-keep class com.aether.client.ui.** { *; }

# org.json is part of the platform, but the JVM unit tests use the real artifact.
-dontwarn org.json.**

# hev-socks5-tunnel reads its configuration by reflection over these fields.
-keep class com.aether.client.tun.TunConfig { *; }