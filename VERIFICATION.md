# Verification

## Automated checks completed

- `:app:assembleDebug`
- `:app:assembleRelease`
- `:app:lintDebug` (no errors; remaining warnings are expected private/reflection API and baseline dependency notices)
- APK manifest inspection: launcher, permissions, foreground services, boot receivers and Xposed metadata are present
- APK Xposed inspection: `targetApiVersion=102`, `MainHook` entry and `com.tencent.mm` scope are packaged
- Legacy API scan: no `de.robv.android.xposed`, API 82, `XSharedPreferences`, old provider or duplicate market implementation remains
- Device spoofing inspection: both `SystemProperties` interception and process-local `Build` static-field replacement are present
- Navigation inspection: Android system back is handled for all Compose child screens
- Navigation hierarchy: dashboard → spoof/tuning; search returns to spoof; advanced settings returns to its source screen
- Partial-save inspection: CSC/SIM and device actions merge only their own fields into the latest persisted config
- Scope inspection: in-app device-target selection uses libxposed 102 `requestScope`, preserving LSPosed authorization

## Device checks recommended

Hardware- and version-dependent behavior cannot be proven by a desktop build. Before daily use, test on the target Samsung device:

1. Install, enable the module in LSPosed, select the recommended scopes and reboot.
2. Verify CSC/SIM spoofing in Galaxy Store, then clear Store data and reopen it.
3. Enable one WeChat feature at a time, force-stop WeChat, reopen it and check the LSPosed log for `WeiTiao` entries.
4. Test bright-screen fast charging while monitoring device temperature; disable it and confirm permissions are restored.
5. Test USB PD pass-through with one selected app and confirm the sysfs value returns to `0` after leaving the app or disabling the feature.

The WeChat implementation retains adaptive class scanning because WeChat class names change between releases. A future WeChat update may require another compatibility update even when the APK itself builds correctly.
