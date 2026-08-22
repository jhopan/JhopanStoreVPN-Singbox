# JhopanLiteVPN Agent Guide

Java/XML Android VPN client. Supports ARM64-only VLESS over WebSocket + TLS normal SNI through Android `VpnService` and local `libbox.aar`.

## Environment

- Android Gradle Plugin 8.6.1, Gradle 8.9, JDK 17, compile/target SDK 35, min SDK 24.
- Module: `:app`; package/application ID: `com.jhopanstore.litevpn`.
- Dependencies are local `app/libs/libbox.aar`, AndroidX Core, and AppCompat. No Kotlin source or test suite exists.
- For native AAR rebuild: Go, Android NDK, Git Bash or WSL, `git`, `jar`, `gomobile`, and `gobind` are required.

## Build

From repository root:

```bash
# Git Bash/WSL: rebuild local ARM64 libbox AAR
bash build_libbox.sh

# README build commands
./gradlew assembleDebug
./gradlew assembleRelease

# Windows commands documented in CLAUDE.md
./gradlew.bat assembleDebug
./gradlew.bat assembleRelease
```

APK outputs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

No test, lint, or CI command is defined in repository. Verify changed code with relevant debug build; use release build when changing ProGuard, resources, manifest, or native dependency wiring.

## Layout

- `app/src/main/java/com/jhopanstore/litevpn/MainActivity.java`: XML UI, VLESS fields, clipboard IO, persisted fields/HWID, ping, traffic display.
- `app/src/main/java/com/jhopanstore/litevpn/VpnService.java`: foreground service, Android TUN, `libbox` lifecycle, network interface callbacks.
- `app/src/main/java/com/jhopanstore/litevpn/PingMonitor.java`: HTTP `HEAD` through `127.0.0.1:10808` every 10 seconds.
- `app/src/main/java/com/jhopanstore/litevpn/core/VlessParser.java`: restricted URI validation/import/export.
- `app/src/main/java/com/jhopanstore/litevpn/core/SingboxConfig.java`: sing-box JSON; `PROXY_PORT` is `10808`.
- `app/src/main/res/layout/activity_main.xml`: single screen UI. `app/src/main/res/values/`: app strings/theme.
- `app/proguard-rules.pro`: preserves libbox/Go/VpnService; debug/info Android logs removed in release.

## Conventions

- Java only. Classes are `final`; utility classes use private constructors; config model exposes `public final` fields.
- Keep one class per file under package-matching directories. Core VPN parsing/config stays in `core/`.
- UI state persists in `SharedPreferences` file `vpn`; async networking uses single-thread `ExecutorService` and posts UI updates through main-thread `Handler`.
- Validate VLESS through `VlessParser.parse()` before connection. Supported URI: `vless://`, `type=ws`, `security=tls`; reject `onering:` SNI.
- Build config JSON through `SingboxConfig.build()` and validate using `Libbox.checkConfig()` before starting service.

## Pitfalls

- `libbox.aar` targets sing-box `v1.11.0`. `build_libbox.sh` retains `with_gvisor,with_clash_api`, removes QUIC/uTLS, and verifies only `jni/arm64-v8a/libgojni.so`. Do not upgrade sing-box without matching libbox Java API migration and APK build.
- ARM64 is enforced by `abiFilters 'arm64-v8a'`; do not add other ABIs without replacing local AAR.
- Release is currently debug-signed: `signingConfig signingConfigs.debug`. Do not present release APK as distribution-ready without release signing work.
- `VpnService` must call `protect(fd)`, exclude own package from TUN, and report network interfaces/default interface; changing these can create VPN routing loops or no-interface failures.
- Manifest foreground service type is `specialUse`; preserve VPN permission and service intent filter when touching service config.
- `cache.db` is generated in app files directory by sing-box. Do not add it to source control.
- Native config and VLESS credentials can reach logs/errors. Never add config JSON or credentials to logging.
