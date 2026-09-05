# JhopanStore VPN

Android Java VPN client by JhopanStore. Scope: VLESS + WebSocket + TLS normal SNI, ARM64 only.

## Features

- 1.2-second JhopanStore launch splash
- Android `VpnService` with persistent foreground notification
- VLESS WebSocket/TLS import/export through clipboard and `.jvs` files; supplied path, SNI, and Host preserved
- Three-dot menu for import/export, HWID copy, and traffic meter on/off
- `allowInsecure=true` by default for compatible Worker bug-domain profiles
- IPv4-first outbound (`prefer_ipv4`); DNS 1.1.1.1 primary, 8.8.8.8 backup, `local` bootstrap
- Stable routing via `override_android_vpn` (Android `protect(fd)`; no interface guessing, works on Qualcomm and MediaTek)
- Honest status flow: Connecting → Checking internet → Connected; failures show a safe reason (no network, DNS, TLS, WebSocket, internet check)
- 24/7 recovery: `START_STICKY`, saved URI auto-reconnect, screen-on auto-heal probe, network-change probe, reconnect cap
- Battery guard dialog: disable battery optimization + open MIUI Autostart
- Traffic meter: pause in background, toggleable in menu

Excluded: QUIC, hotspot sharing, backup servers, rules, failover, wake lock.

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

Measured local build: `libbox.aar` 7.65 MiB; release APK ~23 MiB.

## TUN stack note (under test)

The TUN inbound currently sets `"stack": "system"` instead of the sing-box default `gvisor`.

Measured on a MediaTek device (vivo 1802, LTE):

| metric | gvisor | system |
|---|---|---|
| Total PSS | ~106 MB | ~66 MB |
| HTTP 204 via tunnel | ~0.4–0.9 s | ~0.4–0.5 s |
| stress + screen-off recovery | ok | ok |

Both stacks passed connect, browse, stress, and 30 s screen-off recovery tests. The lighter `system` stack is shipping while long-run daily-use validation continues; if any "some pages won't load" report appears, revert by removing `"stack": "system"` in `SingboxConfig.java` (falls back to gvisor).

HWID is random installation ID hashed with SHA-256. It persists through restart/update but resets after clearing app data or uninstalling.
