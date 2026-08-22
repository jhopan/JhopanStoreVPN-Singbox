# JhopanStore VPN

Android Java VPN client by JhopanStore. Scope: VLESS + WebSocket + TLS normal SNI, ARM64 only.

## Features

- Connect/disconnect through Android `VpnService`
- VLESS URI import/export through clipboard
- Device-local installation HWID
- Per-app traffic total and speed while connected

Excluded: OneRing, uTLS, QUIC, hotspot sharing, backup servers, rules, failover, autostart, wake lock, and keepalive.

## Build

Requirements: JDK 17, Android SDK API 35, Go, Android NDK, Git Bash or WSL.

```bash
bash build_libbox.sh
./gradlew assembleDebug
./gradlew assembleRelease
```

APK outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

`build_libbox.sh` pins stable compatible sing-box `v1.11.0` and creates ARM64 `app/libs/libbox.aar`. It retains `with_gvisor` for Android TUN and `with_clash_api`, required internally for v1.11 libbox service startup. QUIC and uTLS are removed. It strips symbols and validates the AAR contains only `arm64-v8a`. Current Java `VpnService` targets this tested libbox API. Upgrade Sing-box only alongside a separate tested libbox API migration.

Measured local build: `libbox.aar` 7.65 MiB; release APK 22.28 MiB.

HWID is random installation ID hashed with SHA-256. It persists through restart/update but resets after clearing app data or uninstalling.
