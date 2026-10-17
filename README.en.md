# WeiTiao

[中文说明](README.md) · **[Direct Download APK (WeiTiao-v1.4.8.apk · 1.6 MB)](https://github.com/WEIHEWU/WeiTiao/raw/main/releases/WeiTiao-v1.4.8.apk)** · [Releases](https://github.com/WEIHEWU/WeiTiao/releases)

An LSPosed (`libxposed API 102`) & Root enhancement module for Samsung One UI built with **`miuix` (`0.8.8`)**, combining **App Store Region Unlock (CSC/SIM spoofing)**, **Hardware Model Spoofing**, **WeChat Steps & Anti-Recall**, **Screen-On Fast Charging**, and **USB PD Bypass Power**.

Current version: `1.4.8` (`versionCode 28`).

> Fully tested and officially adapted for Samsung One UI 8.5 / One UI 9 (Android 17 / API 37), compatible with all Galaxy devices running One UI.

## Key Features

- **Native Miuix UI & Physics**:
  - Built with `top.yukonga.miuix.kmp:miuix:0.8.8`, featuring collapsible large-title navigation bars, physical overscroll bounce (`overScrollVertical`), G2 continuous smooth corners (`smoothRounding`), and `folmeSpring` physics animations.
- **Store Region Unlock (CSC + SIM Spoofing)**:
  - 6 curated presets (Hong Kong, US Unlocked, Europe Open, Taiwan, South Korea, Mainland China) plus a searchable database of 80 global country/carrier presets;
  - Spoofs CSC and SIM operator info **only for store apps** (Galaxy Store, Theme Store, Samsung Account region services) — **never hooks `android` system framework or `com.android.phone`, keeping physical SIM and baseband 100% native and stable**;
  - One-tap store cache reset and one-tap disable to restore native physical SIM detection.
- **Hardware Model Spoofing**:
  - 126 built-in flagship device profiles across multiple brands, with custom model support and per-app targeting.
- **WeChat Enhancements (LSPosed Hook)**:
  - **Step Modifier**: Monotonic anchor-offset step engine supporting auto-increment and fixed multiplier modes, daily step cap, and cutoff hour without negative jumps;
  - **Anti-Recall**: Blocks message recall and preserves text, photos, voice notes, and videos;
  - **One-Tap Restart**: Force-stops and automatically relaunches WeChat via Root.
- **Screen-On Fast Charging (Root Only, No LSPosed Scope Needed)**:
  - Masks Samsung thermal throttling nodes while screen-on charging, and automatically restores stock `0755` permissions on unplug, disable, or when battery reaches the `44.0°C` safety threshold.
- **USB PD Bypass Power (Root Only, No LSPosed Scope Needed)**:
  - Powers the motherboard directly when a selected game/app is in the foreground and battery exceeds the configured threshold.
- **Ultra-Lightweight**:
  - Release APK is only **1.6 MB**, with O(1) precomputed hash property lookup and zero idle polling wakeups.

## Recommended LSPosed Scopes

When enabled in LSPosed, only the following scopes are recommended (**do NOT check `android` System Framework**):

- `com.sec.android.app.samsungapps` (Galaxy Store)
- `com.samsung.android.themestore` / `com.samsung.android.themecenter` (Samsung Theme Store)
- `com.osp.app.signin` / `com.samsung.android.samsungaccount` / `com.samsung.android.authfw` (Samsung Account Services)
- `com.tencent.mm` (WeChat)

> **Note**: Screen-On Fast Charging and USB PD Bypass Power operate directly via Root (`su`) and require **no** LSPosed scopes. Set WeiTiao to **Unrestricted** in system battery settings so One UI deep-sleep does not block plug-in triggers.

## Build

JDK 17+ and Android SDK (`compileSdk 37`) required.

```bash
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`.
Signing config is read from `local.properties` (`signing.storeFile`, etc.) and automatically falls back to `~/.android/debug.keystore` when absent.

## Disclaimer

For personal learning and research only. Modifying system properties or thermal nodes carries inherent risks; use at your own discretion.

## License

GNU General Public License v3.0 (GPL-3.0-only). See [LICENSE](LICENSE).
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for imported device profile notices.

