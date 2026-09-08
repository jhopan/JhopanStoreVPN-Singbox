# JhopanStore VPN

Android Java VPN client by JhopanStore. Scope: VLESS + WebSocket + TLS normal SNI, ARM64 only.

![icon](design/logo.svg)

## Features

- 1.2-second JhopanStore launch splash with the key logo (green→cyan gradient on dark)
- Custom launcher icon and notification icon matching the splash
- Android `VpnService` with persistent foreground notification
- VLESS WebSocket/TLS import/export through clipboard and `.jvs` files; supplied path, SNI, and Host preserved
- **Offline HWID licensing** (since v1.1.0):
  - Export Locked Config: choose config name, customer HWID, expiry date (dd/mm/yyyy), and lock switch
  - The `.jvs` payload is AES-256-GCM encrypted with a key derived from the customer's HWID — only that device can open it
  - Import auto-detects license payloads (`JLS1:` prefix) via file, clipboard, or open-with
  - Wrong HWID → rejected; expired → rejected
  - Lock ON hides all config fields on the customer device and shows only the config name and expiry
  - Importing the customer's own VLESS URI releases the license automatically
  - Clear Config menu item wipes config + license back to first-run state
- Three-dot menu: import/export, Export Locked Config, Copy HWID, Clear Config, About (Telegram + website links), Traffic Meter
- `allowInsecure=true` by default for compatible Worker bug-domain profiles
- IPv4-first outbound (`prefer_ipv4`); DNS 1.1.1.1 primary, 8.8.8.8 backup, `local` bootstrap
- Stable routing via `override_android_vpn` (Android `protect(fd)`; no interface guessing, works on Qualcomm and MediaTek)
- Honest status flow: Connecting → Checking internet → Connected; failures show a safe reason (no network, DNS, TLS, WebSocket, internet check)
- 24/7 recovery: `START_STICKY`, saved URI auto-reconnect, screen-on auto-heal probe, network-change probe, reconnect cap
- Keep-alive on task removal (`onTaskRemoved` restarts the service if the user swipes the app away)
- Battery guard dialog: disable battery optimization + open MIUI Autostart; stepwise first-run 24/7 setup
- System-kill education dialog with direct settings links, shown once when the OS kills the VPN
- Session traffic meter: counts from zero on every connect, resets on disconnect; service-side sampling survives app close; toggle in menu (allowed only while disconnected; off = sampling skipped entirely)
- Version label shown in the UI

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

CI: GitHub Actions builds the release APK on every push to `main` and publishes it to the rolling `latest` GitHub Release (`JhopanStoreVPN.apk`). Pushing a `v*` tag (e.g. `v1.1.0`) creates a permanent versioned release (`JhopanStoreVPN-v1.1.0.apk`) and marks it latest.

Releases:

- v1.0.0 — initial public release
- v1.1.0 — offline HWID licensing, Clear Config, About dialog, key logo

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

## Licensing notes

HWID is a SHA-256 hash of a random installation ID (24 hex chars). It persists through restart/update but resets after clearing app data or uninstalling — the customer then needs a new license file. Licensing is offline: no server, no remote revocation. To renew or unlock, send a new license file or have the customer import their own VLESS URI (which releases the license).
