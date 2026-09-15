# Vault Health Exporter

A small, self-owned Android app that exports Android **Health Connect** data straight into a
folder you choose inside a **Syncthing-synced Obsidian vault**.

It is deliberately boring and completely local: **no account, no cloud, no analytics, no ads and
no `INTERNET` permission.**

- Package: `com.vaulthealth.exporter`
- Min SDK 26, target Android 14 (API 34), compiled against SDK 36
- Language/stack: Kotlin, Jetpack Compose, Room, DataStore, WorkManager, AndroidX Health Connect

---

## Repository layout

```
health-exporter/          (this repo — never inside the vault)
├── core/                 pure Kotlin/JVM: models, NDJSON, checksums, dedupe, tokens, summaries
│   └── src/test/         unit tests (run without an Android device)
└── app/                  Android app: Health Connect, SAF, Room, DataStore, WorkManager, Compose
```

All deterministic logic lives in `:core` so it is unit-tested on the JVM.

### Build

Requires JDK 17 and the Android SDK (platform 36, build-tools 36.0.0).

```powershell
# Windows
$env:JAVA_HOME  = "path\to\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :core:test            # 35+ unit tests
.\gradlew.bat :app:assembleDebug    # debug-signed APK
.\gradlew.bat :app:assembleRelease  # release-signed APK (uses keystore.properties)
```

### Signing

`keystore.properties` (git-ignored) points at a self-signed keystore:

```properties
storeFile=../keystore/sideload.p12
storePassword=...
keyAlias=vaulthealth
keyPassword=...
```

Generate one if it is missing:

```powershell
keytool -genkeypair -v -keystore keystore\sideload.p12 -alias vaulthealth `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -dname "CN=Vault Health Exporter, O=Self-signed, C=NZ"
```

If `keystore.properties` is absent, `assembleRelease` still produces an unsigned APK; only
`assembleDebug` (signed with the Android debug key) works without it.

---

## Install (sideload)

1. Build or copy `app/build/outputs/apk/release/app-release.apk` to the phone.
2. On the phone, allow installing unknown apps for your file manager / browser.
3. Install the APK.
4. Open **Vault Health Exporter**.
5. Tap **Select folder** and choose the **destination folder** (e.g. a folder inside your
   Syncthing-synced vault). Grant access; the app persists the SAF permission so it survives
   reboots.
   Exports are written **directly into that folder**, with no assumed vault structure:
   - `<chosen>/snapshots/`
   - `<chosen>/deltas/`
   - `<chosen>/Summaries/`
6. Tap **Permissions** and grant the Health Connect reads you want, plus **Activity
   recognition** (required for steps/distance on Android 10+).
7. For a full historical export, tap **History** to grant *read health data history*, then use
   **Historical snapshot**.
8. For scheduled export, tap **Background** to grant *read health data in background*, then pick
   **Daily** or **Weekly**.

### ADB install (optional)

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

---

## Vault output

### Snapshot — `snapshots/health-connect-2026-04-16_to_2026-09-14.ndjson`

NDJSON: one header line, one native record per line, one manifest line.

```json
{"schema":"vault-health-exporter/ndjson","version":1,"kind":"snapshot","exported_at":"2026-09-14T10:00:00Z","source":"health-connect","requested_range":{"start":"2026-04-16T00:00:00Z","end":"2026-09-14T23:59:59.999Z","timezone":"Pacific/Auckland"},"change_token_before":"...","app":{"name":"Vault Health Exporter","version":"1.0.0"}}
{"id":"hc-steps-abc","type":"Steps","source_package":"com.xiaomi.wearable","start_time":"2026-09-14T00:05:00Z","end_time":"2026-09-14T00:30:00Z","zone_offset":"+12:00","values":{"count":842},"samples":[],"stages":[]}
{"id":"hc-hr-def","type":"HeartRate","source_package":"com.xiaomi.wearable","start_time":"2026-09-14T00:00:00Z","end_time":"2026-09-14T00:10:00Z","samples":[{"t":"2026-09-14T00:00:00Z","value":61}]}
{"id":"hc-ex-123","type":"ExerciseSession","source_package":"com.xiaomi.wearable","start_time":"2026-09-14T06:30:00Z","end_time":"2026-09-14T07:15:00Z","values":{"exerciseType":"running","title":"Morning run"},"stages":[{"stage":"SEGMENT_70","start":"...","end":"..."}],"route":{"state":"consent_required"}}
{"kind":"manifest","record_total":1240,"record_counts":{"ExerciseSession":4,"HeartRate":900,"Steps":336},"issue_counts":{"route_consent_required":2,"zero_value":1},"exported_at":"...","requested_range":{...},"checksum":{"algorithm":"sha256","scope":"payload_excluding_manifest","value":"<hex>"}}
```

- `manifest.checksum` covers the **payload** (header + records, excluding the manifest line).
- The sibling `health-connect-2026-04-16_to_2026-09-14.ndjson.sha256` covers the **whole file**
  and is `sha256sum`-compatible: `<hex>  <filename>`.

### Delta — `deltas/health-connect-delta-2026-09-15T020000Z.ndjson`

Header, then bare upsert records, then deletion tombstones, then manifest:

```json
{"schema":"vault-health-exporter/ndjson","version":1,"kind":"delta","exported_at":"...","change_token_before":"tok-1","change_token_after":"tok-2","app":{...}}
{"id":"hc-steps-xyz","type":"Steps","source_package":"com.xiaomi.wearable","start_time":"2026-09-14T22:00:00Z","values":{"count":431}}
{"op":"delete","id":"hc-steps-old","deleted_at":"2026-09-15T01:59:00Z"}
{"kind":"manifest","record_total":2,"upserts":1,"deletions":1,"change_token_before":"tok-1","change_token_after":"tok-2","checksum":{...}}
```

Deltas are never overwritten. If the file name would collide, a `-2`, `-3`, … suffix is used.

### Daily summary — `Summaries/YYYY-MM-DD.md`

Deterministic Markdown, no LLM, no raw data embedded:

```markdown
---
date: 2026-09-14
generated_by: vault-health-exporter
source: health-connect
generated_at: 2026-09-15T02:00:00Z
---
# Health Summary — 2026-09-14

- Steps: 8432
- Distance: 6210.00 km
- Exercise: 47 min (2 sessions)
- Active calories: 512 kcal
- Total calories: 2140 kcal
- Sleep: 7h 12m (deep 1h 20m, REM 1h 05m)
- Heart rate: avg 62 bpm (min 48, max 141)
- Resting heart rate: 54 bpm
- SpO2: 97% (min 95, max 99)
- Weight: 74.2 kg

## Workouts
- 2026-09-14 06:30–07:15 Run 45 min `id=hc-ex-123` `route=available`
```

Sleep is attributed to the **wake** date.

---

## Permissions

The app exports **every Health Connect record type** (41 at the time of writing): steps,
distance, active/total calories, exercise sessions, heart rate, resting heart rate, HRV, SpO₂,
respiratory rate, sleep, speed, steps/cycling cadence, weight, height, body fat, lean/water/bone
mass, basal metabolic rate, body/basal/skin temperature, blood glucose, blood pressure, hydration,
nutrition, elevation, floors, power, VO₂ max, wheelchair pushes, mindfulness, planned exercise and
the cycle-tracking types. Types without a hand-written mapping go through a generic reflective
mapper, so newly added Health Connect types still export.

Requested on demand, individually, from the UI (`Data types X/41` → **Grant**).

Special, separately requested grants:

- `android.permission.health.READ_HEALTH_DATA_HISTORY` — full historical snapshot.
- `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` — scheduled export.
- `android.permission.ACTIVITY_RECOGNITION` — steps/distance on API 29+.

There is **no `INTERNET` permission and no `ACCESS_NETWORK_STATE`** in the built APK.

### Exercise routes (GPS)

Routes are only ever read in the foreground and written to their own immutable file; background
work records `consent_required`/`no_data`.

Reality check on where GPS comes from:

- **Strava does not write GPS to Health Connect.** Its manifest declares only `WRITE_DISTANCE`,
  `WRITE_EXERCISE`, `WRITE_TOTAL_CALORIES_BURNED` and `READ_WEIGHT` — there is no
  `WRITE_EXERCISE_ROUTE`, so Strava can never populate Health Connect routes.
- **Mi Fitness does** (`WRITE_EXERCISE_ROUTE`). Enable Mi Fitness → Health Connect data sharing so
  its routes land in Health Connect; then this app can read them.
- The app first requests `android.permission.health.READ_EXERCISE_ROUTES` on its own (bulk route
  access, added after connect-client 1.1.0, so referenced by raw string). If that is unavailable it
  falls back to the per-session `ExerciseRouteRequestContract`.
- If Health Connect holds no route for a session, nothing can be exported for it — the manifest
  issue counters (`route_consent_required`, `route_no_data`) report this honestly.

---

## Reliability & privacy

- Immutable exports: temp-write → verify read-back → rename. Never overwritten.
- Change tokens are stored in app-private DataStore and advanced **only after** a delta is durably
  written, so a crash can never skip changes.
- An expired/invalid token surfaces a clear "run a fresh snapshot" prompt — the app never guesses.
- WorkManager periodic work plus a boot/app-open catch-up job covers missed runs and reboots.
- Room stores only export history; DataStore stores only config, tokens and pending consent.
- Backups are disabled (`allowBackup=false`).

---

## Acceptance test

On a real Android device:

1. Select the Syncthing vault folder; confirm the three directories appear.
2. Grant history + read permissions, then run a **Historical snapshot** over a range.
   - Confirm one `.ndjson` + one `.ndjson.sha256` in `snapshots/`.
3. Verify the checksum locally and after sync:
   ```powershell
   certutil -hashfile "health-connect-....ndjson" SHA256      # Windows
   # or: sha256sum -c health-connect-....ndjson.sha256
   ```
   Compare with the `.sha256` file and with the `manifest.checksum` (payload only).
4. Record a new workout, then tap **Export now** and confirm a new file in `deltas/`.
5. Confirm a concise `Summaries/YYYY-MM-DD.md` was created/updated.
6. Confirm zero network activity:
   ```powershell
   "%ANDROID_HOME%\cmdline-tools\latest\bin\apkanalyzer.bat" manifest permissions app-release.apk
   # must not list INTERNET or ACCESS_NETWORK_STATE
   ```
   Optionally verify no traffic with `adb shell dumpsys netstats` while using the app.

---

## Out of scope for v1

Cloud upload, accounts, web dashboard, medical interpretation, training advice, server API,
multi-user support, Play Store release, and a NAS-side importer.
