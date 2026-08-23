# JhopanLiteVPN Agent Guide

Java/XML Android VPN client. ARM64-only VLESS over WebSocket + TLS normal SNI through Android `VpnService` and local `libbox.aar`.

## Environment

- Android Gradle Plugin 8.6.1, Gradle 8.9, JDK 17, compile/target SDK 35, min SDK 24.
- Module `:app`; application ID `com.jhopanstore.litevpn`.
- Dependencies: local `app/libs/libbox.aar`, AndroidX Core, AppCompat. No Kotlin source or test suite.
- Native AAR rebuild requires Go, Android NDK, Git Bash or WSL, `git`, `jar`, `gomobile`, and `gobind`.

## Build

From repository root:

```bash
bash build_libbox.sh
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew.bat assembleDebug
./gradlew.bat assembleRelease
```

APK outputs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

No test or CI command exists. Build debug after Java/config changes; build release after ProGuard, resources, manifest, native AAR, or branding changes.

## Layout

- `MainActivity.java`: XML UI, VLESS fields, clipboard and `.jvs` file import/export, persisted form/HWID, traffic display, three-dot menu.
- `SplashActivity.java`: 1.2-second launch splash.
- `VpnService.java`: foreground VPN, Android TUN, libbox lifecycle, network callbacks, heartbeat, tunnel recovery.
- `core/VlessParser.java`: restricted URI validation/import/export.
- `core/SingboxConfig.java`: sing-box JSON; `PROXY_PORT` is `10808`.
- `activity_main.xml`: single screen UI; `AndroidManifest.xml` registers `.jvs` MIME view intent.
- `app/proguard-rules.pro`: preserves libbox/Go/VpnService; debug/info logs removed in release.

## Conventions

- Java only. Classes are `final`; utility classes have private constructors; config model exposes `public final` fields.
- Keep one class per file under package-matching directories. Core VPN parsing/config stays in `core/`.
- UI state persists in SharedPreferences `vpn`; service status is in `vpn_status`; async work uses a single-thread `ExecutorService` and main-thread `Handler`.
- Validate VLESS through `VlessParser.parse()` before connection. Supported URI: `vless://`, `type=ws`, `security=tls`; reject custom SNI prefixes.
- Preserve supplied WebSocket path, SNI, and Host. `.jvs` content is one UTF-8 VLESS URI, validated before use.
- Build config through `SingboxConfig.build()` and validate with `Libbox.checkConfig()` before service start.

## Pitfalls

- `libbox.aar` targets sing-box `v1.11.0`; `build_libbox.sh` retains `with_gvisor,with_clash_api`, removes QUIC/uTLS, and validates only `jni/arm64-v8a/libgojni.so`.
- ARM64 is enforced by `abiFilters 'arm64-v8a'`; do not add ABI without matching native AAR.
- Release uses debug signing. Do not present it as distribution-ready before release signing.
- `VpnService` must call `protect(fd)`, exclude own package from TUN, and report network interfaces/default interface; changing these can create routing loops.
- Keep service status, heartbeat, tunnel probe, and foreground cleanup aligned; notification `Connected` must not outlive a closed TUN/core.
- `cache.db` is generated in app files directory. Never source-control it.
- Native config and VLESS credentials can reach logs/errors. Never log URI, JSON config, UUID, or credentials.
- Commit every verified change and push `main` to `https://github.com/jhopan/JhopanStoreVPN-Singbox.git`.
