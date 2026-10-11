# Aether core is started through ProcessBuilder against an extracted ELF executable;
# nothing about it is reflected on, so the default rules are enough. Kept explicit so
# a future R8 run cannot quietly drop the VpnService entry points.
-keep class com.aether.client.service.AetherVpnService { *; }
-keep class com.aether.client.MainActivity { *; }