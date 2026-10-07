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

### Version 3.0: background scanner fixes

- **Fixed "Last scan timed out"**: exchange replies were posted to the hidden WebView with `web.post()`, which Android never runs for a WebView that is not on screen. Replies now go through the main-thread handler, so scans complete.
- **Watchdog instead of a blind 4-minute timer**: the page reports progress ("Scanning 12/40"); a scan is only stopped if no data arrives for 90 s. If the page is not ready it is reloaded and retried within seconds.
- **Pop-up alerts**: new high-importance "Ready setups" channel with sound and vibration. Brief pop-up shows "SOL LONG Ready · Grade A"; detailed/expanded view shows entry, SL, TP, R:R, setup type and expiry. Several setups are grouped with a summary.
- **No setup = no alert.** The status notification Android requires is now silent and minimised.
- **Tap an alert** to open the app on that scan's results (same entry/SL/TP as the alert).
- **Scanner performance** keeps re-checking waiting and open trades after every scan until expiry, stop or target (no 24h cut-off; 1H candles fill gaps if the phone was off). New "Being tracked" list shows each with its plan and current R.

### Version 4.0: evidence (permanent history)

- **Native database on the phone** (`SignalDb.java`, SQLite) replaces browser storage. The app screen and the background scanner share one instance, so records are never overwritten or lost. Nothing is deleted automatically; there is no 400-signal limit.
- **Every Ready signal is frozen** with its full plan and market picture: entry, stop, target, missed level, expiry, level type/strength/tests, sweep extreme, break price, target level, 5m/15m/1H/4H ATR, 1H/4H trend, BTC, funding, OI, volume, session, grade breakdown, reasons and scanner version. Only outcome fields can change later.
- **Outcome tracking** replays closed candles after each signal's last checkpoint (5m, then 15m, then 1H when older): Waiting, Open, Win, Loss, Missed, Expired, **Ambiguous** (stop and target in one candle: shown as a range, left out of win rate) and **Unresolved** (no candles for the period).
- **MFE/MAE** in price and R, R milestones (+0.5/+1/+1.5/+2R), wick vs close stops, and 24h follow-up after a stop (did it reach the target anyway?).
- **Dashboard**: Today, Yesterday, 7 days, 30 days, All, Custom dates; win rate, expectancy, total R, profit factor, worst losing run, max drawdown, sample-size label; breakdown by grade, setup type, trigger, level type, direction, session and exchange; full signal list with tap-to-open detail.
- **Scan log**: every scan (manual, auto, background) with coins requested/completed/failed, Ready count and new signals.
- **Backup**: Export CSV, Back up (JSON file anywhere), Restore from file (merges, never overwrites), one automatic backup per day inside the app (last 14 kept), Archive/Un-archive and Delete period (type DELETE to confirm).
- **Fixed signing key**: builds are signed with your own key from GitHub secrets, so updates install over the app and keep the history.
- Removed test placeholder classes that had been left in the source folder.

#### One-time signing setup
1. In GitHub open your repo > **Settings** > **Secrets and variables** > **Actions** > **New repository secret**.
2. Add the four secrets from `MannerWein-signing-secrets.txt`: `MW_KEY_ALIAS`, `MW_KEYSTORE_PASSWORD`, `MW_KEY_PASSWORD`, `MW_KEYSTORE_BASE64`.
3. Never commit the `.jks` or the secrets file to the repo. Keep both private.
4. The first signed build needs one last uninstall of the old (debug-signed) app. After that, every update installs over it.

### Version 4.1: entry and stops

- **New steps to Ready**: Approaching, At level, Swept, Broke structure, Retesting, Ready. Ready now needs a confirmed retest: price pulls back into the break point, the displacement candle or the level, holds, and a 5m/15m candle closes back with a rejection. Entry is that close (no more entering on the first break).
- **Sweep validation**: depth in ATR, close position in the candle, closes beyond the level, volume and clearance. Graded clean / acceptable / weak; "breakout" (too deep) and "acceptance" (3+ closes beyond) are rejected as not sweeps.
- **Break of structure quality**: real pivot that was not already broken, prominence, body size vs candle and vs average, distance beyond the swing, volume and speed. Graded strong / acceptable / weak; only strong or acceptable breaks count.
- **Playbooks**: Dual (15m and 5m both broke structure), Standard 15m, Fast 5m. Fast 5m needs a solid level (12/20+), a clean sweep and a strong break, at half risk. Retests for 15m/dual setups are judged on 15m closes (5m noise alone does not cancel them).
- **Logical stop**: the lowest (long) / highest (short) of sweep extreme, retest low, level zone and range edge, plus 0.5 x 15m ATR, never closer than 1 x 15m ATR. Position size shrinks so the account risk stays the same.
- **Stop distance test**: every signal is also tracked with four stops (structure only, +0.5, +0.75, +1.0 x 15m ATR) on the same entry, target and expiry. Results are compared in Scanner performance.
- **Range setups**: must be at the 1H range edge; first target is the range midpoint (or a strong level before it).
- **Reversals**: strong level (14/20+), clean sweep, strong 15m break, R:R 2+, not against a strong BTC move, half risk.
- **Grade**: new Trigger quality factor (sweep, break, retest) worth 20 points.
- **Scanner performance**: shows the current scanner version by default (older versions can be included), plus breakdowns by playbook, sweep quality, break quality, entry model, retest timeframe and what set the stop. CSV has the new fields.
- **Checklist**: new must-pass Retest question; sweep, break and stop questions updated to the new rules.
- Database schema 2 adds the stop-variant columns; the upgrade keeps all existing history.

### Version 4.2: targets, ratings and analysis

- **Target ladder**: T1 nearest obstacle (mapped level or internal 15m swing), T2 first meaningful level (the target used), T3 major liquidity beyond (strong level, previous day/week high/low, 4H swing). Range setups target the range midpoint first.
- **Room checks**: rejected if a strong level blocks the path before the minimum R:R, if the target needs more than 1.2x the coin's 5-day average daily range, or if R:R after fees is below the minimum ("not enough room to ..."). 2R is only used when no meaningful level is ahead, and is labelled. Warnings when the target is bigger than the move left in today's range, when weaker levels are in the way, and when BTC is close to its own opposing level.
- **Four ratings** (0-100): Market, Level, Trigger, Trade plan. Ready needs all four at 50+; earlier stages need Market and Level at 50+. The **grade comes from the weakest rating** (A 70+, B 55+, C below); the card shows the confidence (weakest) score. The old points total is kept as the score breakdown.
- **Market regime** recorded with every scan and signal: BTC trend, volatility (BTC ATR vs 2 days ago), alt breadth (share of coins beating BTC), median funding; plus BTC's nearest support/resistance. Shown on the Market card.
- **Paper tests (no alerts)** for every signal, replayed on the same candles: exit models (full to target, half at +1R, break-even after +1R, trail after +1R, exit at T1, fixed 1R/1.5R/2R) and entry variants (enter on the break with no retest, limit at the level zone), alongside the v4.1 stop-distance test.
- **Validation panel**: compares each test with the current rule; calls out a leader only after 30 closed trades and suggests a rule change only after 100 trades and +0.15R per trade. Nothing changes automatically.
- **More breakdowns**: weakest rating, confidence band, level strength band, BTC aligned/neutral/opposed, OI supportive/neutral/conflicting, funding, volume band, each regime dimension, target type.
- **Audit view** for each signal: chart of the trigger candles with sweep, break and retest marked and entry/SL/TP/level/T1/T3 lines, plus the R:R, stop and target maths, room, ratings, regime, outcome times, every paper test and data quality.
- **Richer alerts**: setup and level, entry zone, T1/T2, why it is Ready, why this trade, what cancels it, what is still weak and the weakest rating.
- Open details and audits stay open when the list refreshes. CSV adds ratings, ladder, room, regime and every paper-test result.

#### Version 4.3: completing the A-Z plan
- **Retest volume quality**: pullback volume vs the pre-break average and confirming-candle volume vs the pullback. Graded confirming / mixed / poor, worth 10 points of the Trigger rating, shown on the card, in details, audit and a new breakdown.
- **Close-only stop test** (paper): exits only when a candle closes beyond the stop, at that close (losses can exceed 1R). Compared with the normal touch stop in the Exit test and Validation panel.
- **Swing trailing test** (paper): after +1R, trails behind confirmed swing points (2 candles each side), alongside the existing last-3-candles trail.
- **Historical target reach**: each card shows how often similar past signals (same playbook and setup type, widening if fewer than 10) reached T1, T2 and T3. A Target reach table on the dashboard shows the same by playbook. Information only, never blocks a trade.
- **More statistics**: median R, standard deviation and downside deviation.
- Alerts include the history line. CSV adds the new fields.

#### Version 4.4: scanner repair (no new indicators)
- **Weak first break no longer blocks a later strong break**: every close beyond the swing (up to 12 candles after the reclaim) is graded and the first strong/acceptable one is used.
- **Developing setups are remembered**: once a level is swept it is re-checked on every scan for up to 6 hours, even after price moves away from it, until it becomes invalid or expires. Proximity only discovers setups now.
- **Retest replayed to the end**: a close through the retest zone or the sweep extreme after a confirmation cancels it.
- **Fill checked before "missed"**: a setup whose entry was touched is no longer called "missed before a fill"; if price has since run halfway to target it is "Ran away", otherwise Ready with a note that the entry was already touched.
- **Tracking from the alert minute**: signals start at the next whole minute and are replayed on 1m candles (about 16 hours), then 5m/15m/1H. A candle that started before the signal is never used; if a coarser candle has to be skipped, the signal says so.
- **Hard vs blocked vs warning**: dead setups (invalid, expired, missed, not a real sweep, no room) are dropped. Alive setups that are not tradeable yet (R:R, obstacle, playbook rule, crowding, thin book) stay in Watching with "Needs: ...". Ratings, daily range and volume are warnings only. Up to 3 Ready and 5 Watching.
- **Shorter cards**: Enter/Next, Plan, Invalid if, Expires, one reason and one warning. Entry zone, targets, stop, size, history, ratings and everything else moved to Details.
- **One-line summary** with the main blocker, and a funnel in the scan log.
- Historical target reach uses the current scanner version only.

