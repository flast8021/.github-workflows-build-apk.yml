# Manner Wein — Android App

Your scanner, checklist, calculator, and risk-management tool, wrapped as an Android app with a new
**"Aurora Glass"** look: a deep violet/cyan gradient background, frosted glass cards, glowing pill
buttons and badges, and a Space Grotesk heading font. All layout structure, element IDs, and every
line of calculator/scanner/risk JavaScript are unchanged — only colours, shapes, fonts and shadows
were restyled, so functionality is identical to the HTML you supplied.

## Important: this is a source project, not a built APK

This sandbox has no Android SDK, no build tools (aapt2/d8/apksigner), and no outbound internet
access to download them, so an actual `.apk` could not be compiled or tested here. Two ways to get
a real installable APK from this project:

### Option A — GitHub Actions (no local install needed)
1. Create a new GitHub repository and push this folder to it.
2. GitHub Actions will automatically run `.github/workflows/build-apk.yml`, which installs the
   Android SDK + Gradle and builds a debug APK on GitHub's servers.
3. Open the finished workflow run → **Artifacts** → download `manner-wein-debug-apk`.
4. Copy the `.apk` to your Android phone and install it (enable "install unknown apps" for your
   file manager/browser the first time).

### Option B — Android Studio
1. Install Android Studio (bundles the SDK and build tools).
2. Open this folder as a project.
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. The APK appears in `app/build/outputs/apk/debug/app-debug.apk`.

A debug APK is signed with Android's debug key — fine for installing on your own device, not for
Play Store distribution.

## What's inside
- `app/src/main/assets/index.html` — your original tool with the new theme layer and nothing else
  changed in the markup or logic.
- `MainActivity.java` — loads the HTML in a locked-down WebView (no file:// access, no mixed
  content) and bridges **Export CSV** to Android's native "Save As" dialog, since browser-style
  downloads don't work the same way inside an app.
- Back button navigates the WebView history before exiting the app.

## Known limitations (carried over, not introduced by this wrapper)
- Scanner and live data still need internet and the same external proxy/exchange endpoints as the
  web version.
- The funding-credit, input-validation, and "risk check passed" wording issues identified in the
  earlier risk-tool report are unchanged — they live in the calculator JavaScript, which this
  wrapper does not modify.
- Not tested on a physical device from this environment; test before relying on it for live trades.

### Version 2.0: background scanner

- **Background scanner card** (Scanner section, app only): On/Off switch and exchange (MEXC by default, or Binance / Bybit).
- When on, `ScanService` (a foreground service with a permanent "Scanner running" notification) runs the same scanner code as the app every 5 minutes, using your scan settings and the calculator's fees, minimum R:R, balance and risk.
- **Alerts only for Ready setups**: one notification per new Ready signal with entry, SL, TP, R:R and expiry.
- **Quiet hours 22:00 to 06:00 phone time**: no scans, no alerts. It resumes automatically at 06:00.
- **Off stops everything**: the service, timers, alarms and wake lock. The notification also has a Stop button.
- **Scanner performance is stored on the phone** and updated by background scans; the app shows it when opened.
- Exchange requests are made by the phone itself (`MWNative.httpGet`), so no proxy is needed in the app. Only the MEXC, Binance and Bybit API hosts are allowed.
- Restarts after a reboot or app update if it was on.

**After installing:** allow notifications when asked, then tap **Battery settings** in the card and set Manner Wein to Unrestricted / Don't optimise. Samsung/Xiaomi phones may also need the app removed from "sleeping apps".

New files: `ScanService.java`, `NativeBridge.java`, `AppWebClient.java`, `Prefs.java`, `BootReceiver.java`, `res/drawable/ic_stat.xml`.
