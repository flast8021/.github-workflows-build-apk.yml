# Manner Wein — Android App (v5.0)

Trade calculator, risk management and trade log for crypto futures, packaged as an Android app.
The scanner, background scanner, scanner performance history and checklist were removed in v5.0.

## What's in the app
- **Trade Calculator**: coin and LONG/SHORT at the top, then entry, stop loss, take profit, size and leverage.
  Results sit directly under the inputs (net profit at TP, net loss at SL, R:R, risk % of balance, plus ROE,
  size, margin, move %, fees, funding and estimated liquidation).
- **Live result strip** above the bottom tabs: shows net TP, net SL, R:R and a risk-colour chip while you type.
  Tap the chip to open Risk Management.
- **Account, fees & rules** (collapsible): balance, max risk %, margin mode, min R:R, maker/taker fees,
  entry order type, maintenance margin, funding rate and payments. These are remembered on the phone.
- **Suggest amount & leverage**: sizes the trade to your max risk %, keeps margin at or under 20% of balance and
  liquidation well past the stop. Risk is halved after 2 losses in a row in your log.
- **Risk Management**: the same risk checks as before, plus a warning when R:R after fees is below your minimum.
- **Actions & Logs**: Save to Log, Copy Summary, Export CSV, Copy All Logs, Reset, Clear Log, delete with Undo.
  Trades saved in earlier versions are kept (same storage key).
- **Look**: glass neumorphic UI in the navy/blue logo colours.

## Build (GitHub Actions)
Every push runs `.github/workflows/build-apk.yml`. Download the APK from the run's **Artifacts** (`manner-wein-apk`).

### Signing (needed so updates install over the app and keep the log)
Repo **Settings > Secrets and variables > Actions** must contain:
`MW_KEYSTORE_BASE64`, `MW_KEYSTORE_PASSWORD`, `MW_KEY_ALIAS`, `MW_KEY_PASSWORD`.
With these present the workflow builds a signed release APK (`manner-wein.apk`).
Without them it falls back to a debug APK (`manner-wein-debug.apk`), which cannot update a signed install.
Never commit the `.jks` file or the passwords to the repo.

## Project layout
- `app/src/main/assets/index.html` — the whole app UI and logic (calculator, risk check, log).
- `app/src/main/assets/logo.png` — logo used in the header.
- `MainActivity.java` — hosts the page in a locked-down WebView and bridges Export CSV to Android's "Save as" picker.
- `AppWebClient.java` — serves the assets and blocks file access.

## Version history
- **5.0**: Scanner, background scanner, scanner performance and checklist removed. Calculator, risk management
  and log only. Coin and direction moved to the top of the calculator. New glass neumorphic UI with results
  under the inputs and a live result strip. Only the INTERNET permission is kept. Workflow builds a signed APK.
- **4.x**: Scanner, background scanner and scanner performance (removed in 5.0).
