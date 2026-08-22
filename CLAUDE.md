# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build the clean ARM64 libbox (Go, gomobile, Android NDK, Git Bash/WSL required)
bash build_libbox.sh

# Build APKs on Windows
./gradlew.bat assembleDebug
./gradlew.bat assembleRelease
```

## Architecture

- Java-only Android/XML project, API 24+, ARM64 only.
- `MainActivity.java` handles VLESS fields, clipboard import/export, persistent installation HWID, optional ping, and traffic display.
- `VpnService.java` bridges Android TUN VPN APIs to native `libbox` lifecycle.
- `core/VlessParser.java` accepts only VLESS WebSocket/TLS URI with normal SNI; `core/SingboxConfig.java` builds matching sing-box JSON.
- `PingMonitor.java` sends a small HTTP `HEAD` request via local mixed proxy at `127.0.0.1:10808` to `http://dns.google.com` every 10 seconds while enabled and connected.
- `app/libs/libbox.aar` is a local ARM64 native dependency. `build_libbox.sh` pins tested compatible sing-box `v1.11.0`, retains gVisor for Android TUN and Clash API because v1.11 libbox requires it internally to create a service. QUIC/uTLS are removed. Script strips symbols, requires no OneRing patch, and validates its ABI. Upgrade requires a separate libbox API migration and app build test.
