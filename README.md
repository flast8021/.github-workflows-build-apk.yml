# Manner Wein — Android App (v5.3)

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

## v5.1 exchange-assisted calculator
- On startup the app loads the selected exchange's public USDT perpetual contract list. MEXC is the default; Binance and Bybit are selectable.
- Coin search is populated from the selected exchange. Selecting a coin loads the public maximum leverage where the exchange exposes it, current funding rate, funding interval and available public fee metadata.
- MEXC and Bybit expose symbol leverage in public instrument metadata. Binance does not expose the user's symbol leverage bracket without authenticated account access, so the app uses the public/default 125x ceiling and clearly treats it as a ceiling, not a recommendation.
- Fee values are auto-filled from public contract metadata when available, otherwise exchange defaults are used. Personal VIP/promotional fee tiers may differ, so maker/taker remain editable.
- Negative funding rates are supported.
- Amount and initial leverage are calculated automatically from account balance, max risk and stop distance. Moving the leverage slider recalculates the required margin while keeping the same risk-sized position value.
- Saved trades now use the phone's local SQLite database (`manner_wein_trades.db`). Existing v5.0 localStorage trades are migrated when found. The database is not cleared by normal app updates; trades remain until deleted in the app (or Android app data is manually cleared/uninstalled).
- Market access is read-only and uses only public HTTPS endpoints. No API keys or trading permission are stored.

## v5.2 always-fresh coin data
- Coin list loads on app open (MEXC default), on exchange change, and again if older than 30 minutes.
- Every coin selection makes fresh per-coin requests:
  - MEXC: contract detail (leverage range, maker/taker, maintenance margin), funding rate + interval + next settlement, mark price.
  - Binance: premium index (funding, next funding, mark price), funding interval. Leverage uses the 125x ceiling (per-coin brackets need an API key); standard fees.
  - Bybit: instrument info (leverage range, funding interval), ticker (funding, next funding, mark price), risk limit (maintenance margin); standard fees.
- The previous coin's fees/funding are cleared the moment a new coin is chosen, so old numbers are never shown as new.
- Selected coin refreshes every 60 s while on screen and when returning to the app. "Updated hh:mm:ss" and a refresh button are shown.
- A failed refresh is shown in amber with the time of the last good data. Late replies for an older selection are ignored.
- MEXC requests go direct from the phone; if that fails the Cloudflare worker (mw-scanner.flast8021.workers.dev) is used as a fallback proxy.
- Negative funding can be typed (full number keyboard).
- Suggested leverage never exceeds the coin's maximum.

## v5.3 safer risk checks
- Max risk per trade must be 0.1%–50%; anything else is rejected with an error.
- Risk above 5% of balance makes the Risk Management card, "Risk of balance" box and bottom risk chip flash red until fixed. Tapping the risk chip opens a risk-details pop-up.
- Amount and leverage are only re-sized when you change entry, stop loss, balance or max risk — never by the 60-second refresh.
- Maint. margin resets on every coin/exchange change; 0.5% default when the exchange doesn't give it (always on Binance). Bybit uses its lowest risk tier.
- Fees, funding and maint. margin you type are kept on refresh, with an info button and "Use the exchange value" link.
- Binance leverage shows "Check on exchange". Amber info buttons mark any value not loaded from the exchange.
- Picking a new coin clears entry, SL, TP, amount and leverage, and scrolls to the LONG/SHORT selector.
- Stop loss and take profit are optional. Without a stop, a red "No stop loss set" item shows the worst-case loss.
- Desktop/tablet layout (same file is used for the web version); logo embedded in index.html.
