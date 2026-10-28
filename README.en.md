# WeiTiao

[中文说明](README.md) · **[Direct Download APK (WeiTiao-v1.5.3.apk · 1.6 MB)](https://github.com/WEIHEWU/WeiTiao/raw/main/releases/WeiTiao-v1.5.3.apk)** · [Releases](https://github.com/WEIHEWU/WeiTiao/releases)

An LSPosed (`libxposed API 102`) & Root enhancement module for Samsung One UI built with **`miuix` (`0.8.8`)**, combining **Native COW System Property Spoofing Engine**, **App Store Region Unlock (CSC/SIM spoofing)**, **Per-App Hardware Model Spoofing (3-Year Pure Flagships)**, **One-Tap Samsung One UI `CscFeature` Presets**, **WeChat Steps & Cached Anti-Recall**, **Screen-On Fast Charging with Live Telemetry**, and **USB PD Bypass Power**.

> Fully tested and officially adapted for Samsung One UI 8.5 / One UI 9 (Android 17 / API 37), compatible with all Galaxy devices running One UI.

<p align="center">
  <img src="docs/preview-dashboard.svg" width="100%" alt="WeiTiao Control Center & Self-Test Probe Preview" />
</p>

<p align="center">
  <img src="docs/preview-features.svg" width="100%" alt="WeiTiao 4 Core Architectural Pillars" />
</p>

## Key Features

- **Native Miuix UI & 3×2 Single-Screen Control Center**:
  - Built with `top.yukonga.miuix.kmp:miuix:0.8.8`, featuring collapsible large-title navigation bars, physical overscroll bounce (`overScrollVertical`), G2 continuous smooth corners (`smoothRounding`), and `folmeSpring` physics animations;
  - Top Hero status card integrates runtime mode, battery temperature, live charging power (`W`), and battery level; the 3×2 quick tiles sync with a 3-tab segmented parameter card below and provide instant prerequisite guidance if LSPosed or Root is not yet active.
- **Native COW System Property Engine (`NativePropCow`)**:
  - Solves the longstanding issue where Apple/iPhone and cross-brand spoofing fails in reinforced/anti-tamper apps (e.g., Tencent Legu / Jiagu, Coolapk, QQ);
  - Parses `/proc/self/maps` and re-maps property regions via `mmap(MAP_PRIVATE | MAP_FIXED)` into private Copy-On-Write pages, directly rewriting `prop_info` memory and atomic sequence numbers;
  - Native libc (`__system_property_get`) and Java (`Build.*`) return 100% identical values with zero discrepancies, executing in `~1.5ms`.
- **Decoupled Store & Model Spoofing + Per-App Independent Device Models**:
  - **Fully Decoupled**: Store Region Spoofing (`enabled`), Device Model Spoofing (`spoofDevice`), and Custom `CscFeature` / `SystemProperties` operate completely independently;
  - **Per-App Device Models**: In addition to a global target model, tap the model badge on any checked app in the Target Apps sheet to **assign a dedicated device model per app** (e.g., spoof ROG 9 Pro for games to unlock 120/144Hz modes, while spoofing iPhone 17 Pro Max for social apps);
  - **Auto Scope Sync & One-Tap Force-Stop**: Automatically syncs LSPosed scopes via Root SQLite (or Xposed API 102 `requestScope`) and force-stops target apps upon model change so spoofing takes effect immediately on next launch.
- **Store Region Unlock (CSC + SIM Spoofing)**:
  - 6 curated presets (Hong Kong `TGY`, US Unlocked `XAA`, Europe Open `EUX`, Taiwan `BRI`, South Korea `KOO`, Mainland China `CHC`) plus a searchable database of 80 global country/carrier presets;
  - Spoofs CSC and SIM operator info **only for store apps** (Galaxy Store, Theme Store, Samsung Account region services) — **never hooks `android` system framework or `com.android.phone`, keeping physical SIM and baseband 100% native and stable**;
  - One-tap store cache reset and one-tap disable to restore native physical SIM detection without affecting device model spoofing.
- **One-Tap One UI `CscFeature` Presets & Self-Test Probe**:
  - Built-in switches for popular hidden Samsung One UI features: **Native Call Recording**, **Camera Shutter Sound Menu**, **Status Bar Real-Time Network Speed**, and **Quick Panel Data Usage**, alongside a visual Key-Value quick-add bar and JSON editor;
  - Built-in **Environment & Spoof Self-Test Probe** in Settings to verify active `Build.MODEL`, `ro.csc.sales_code`, persistent Root shell status, and active LSPosed hooked processes in real time.
- **WeChat Enhancements (Step Modifier + Cached Anti-Recall)**:
  - **Step Modifier**: Monotonic anchor-offset step engine supporting auto-increment and fixed multiplier modes, 4 quick tier presets, live step conversion preview, daily cap, and cutoff hour without negative jumps;
  - **Anti-Recall with Version-Keyed DEX Cache**: Blocks message recall and preserves text, photos, voice notes, and videos; caches resolved DEX method signatures per WeChat `versionCode + lastUpdateTime` for **0ms instant startup** on subsequent launches.
- **Screen-On Fast Charging & USB PD Bypass Power (Persistent Root Shell Pool, No LSPosed Scope Needed)**:
  - **Persistent Interactive `su` Session**: `RootShell` reuses a persistent interactive `su` pipeline (with automatic one-shot fallback), eliminating process-fork overhead during periodic thermal and bypass checks;
  - **Live Charging Telemetry & Custom Thermal Cutoff**: Displays real-time charging wattage (`W`), current (`mA`), and voltage (`V`) in both the Power panel and foreground notification; supports customizable safety thermal cutoff threshold from `38°C` to `48°C` (default `44°C`), automatically restoring stock `0755` permissions on unplug or cutoff;
  - **USB PD Bypass Power**: Powers the motherboard directly when a selected game/app is in the foreground and battery exceeds the configured threshold.

## Recommended LSPosed Scopes

When enabled in LSPosed, only the following scopes are recommended (**do NOT check `android` System Framework**):

- `com.sec.android.app.samsungapps` (Galaxy Store)
- `com.samsung.android.themestore` / `com.samsung.android.themecenter` (Samsung Theme Store)
- `com.osp.app.signin` / `com.samsung.android.samsungaccount` / `com.samsung.android.authfw` (Samsung Account Services)
- `com.tencent.mm` (WeChat)
- Plus any target apps selected under **Model Spoof Target Apps** (automatically synced to LSPosed when Root is available)

> **Note**: Screen-On Fast Charging and USB PD Bypass Power operate directly via Root (`su`) and require **no** LSPosed scopes. Set WeiTiao to **Unrestricted** in system battery settings so One UI deep-sleep does not block plug-in triggers.

## Verification & Troubleshooting

1. **How can I verify that spoofing and LSPosed hooks are active?**
   - Open **Settings → Environment & Spoof Self-Test Probe** to inspect the live `Build.MODEL`, `ro.csc.sales_code`, Root shell status, and the list of processes reporting active hook heartbeats.
2. **Why does a target app still show my old device model after switching models?**
   - Android apps cache `android.os.Build` and system properties when their process starts. WeiTiao automatically force-stops target apps via Root when you select a new model or save target apps; you can also tap **Restart Spoof Target Apps** in the Region & Model panel at any time.
3. **Does disabling Store Region Spoofing turn off Device Model Spoofing?**
   - **No**. Store Region Spoofing (`enabled`) and Device Model Spoofing (`spoofDevice`) are completely decoupled and can be toggled independently.

## Build

JDK 17+ and Android SDK (`compileSdk 37`) required.

```bash
./gradlew testDebugUnitTest assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`.
Signing config is read from `local.properties` (`signing.storeFile`, etc.) and automatically falls back to `~/.android/debug.keystore` when absent. The GitHub Actions release workflow builds from source, runs unit tests, and publishes both the APK and its `.sha256` checksum.

## Disclaimer

For personal learning and research only. Modifying system properties or thermal nodes carries inherent risks; use at your own discretion.

## License

GNU General Public License v3.0 (GPL-3.0-only). See [LICENSE](LICENSE).
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for imported device profile notices.
