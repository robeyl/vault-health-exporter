# Vault Health Exporter

A small, self-owned Android app that exports **Health Connect** data into a folder you choose —
typically inside a Syncthing-synced Obsidian vault.

Completely local: **no account, no cloud, no analytics, no ads, and no `INTERNET` permission.**

```
Health Connect ──► Vault Health Exporter ──► <your folder>/snapshots/*.ndjson
                                             <your folder>/deltas/*.ndjson
                                             <your folder>/Summaries/YYYY-MM-DD.md
```

- Package: `com.vaulthealth.exporter`
- Min SDK 26 · target Android 14 (API 34) · compiled against SDK 36
- Kotlin · Jetpack Compose · Room · DataStore · WorkManager · AndroidX Health Connect

---

## Features

- **Onboarding** — pick the destination folder once via Storage Access Framework; the grant is
  persisted across reboots.
- **Historical snapshot** — one immutable NDJSON file (header → one native record per line →
  manifest) plus a `sha256sum`-compatible `.sha256`, over `30d` / `90d` / `365d` / **All time** /
  a custom range.
- **Incremental deltas** — driven by Health Connect **change tokens**, with upsert records and
  deletion tombstones. Tokens are advanced only after a file is durably written, so a crash can
  never skip data.
- **Daily summaries** — deterministic Markdown, no LLM, no raw data embedded. Empty days produce
  no note.
- **Scheduled export** — daily or weekly via WorkManager, with catch-up after reboots.
- **Watcher** — optional foreground service that polls for changes every 1/5/15 minutes for
  near-immediate export. Health Connect has no push API (and WorkManager's floor is 15 minutes),
  so a short poll is the only way to get close to real time; it is off by default.
- **All record types** — every Health Connect record type (41 today) is exported. Types without a
  hand-written mapping go through a generic reflective mapper, so newly added types still export.
- **GPS routes** — opt-in, foreground-only; see [Exercise routes](#exercise-routes-gps).

---

## Privacy

- **No `INTERNET` permission, no `ACCESS_NETWORK_STATE`.** Verifiable in the shipped APK:
  ```bash
  apkanalyzer manifest permissions app-release.apk
  ```
- Backups are disabled (`allowBackup=false`). Only configuration, export history and change
  tokens are stored, privately, on-device.

---

## Install

Download `app-release.apk` from the [latest release](../../releases/latest), copy it to your
phone, allow "install unknown apps" for the app you open it with, and install.

Or over ADB:

```bash
adb install -r app-release.apk
```

### First run

1. **Change** → choose your destination folder (e.g. a folder inside your synced vault).
   The app creates `snapshots/`, `deltas/` and `Summaries/` inside it.
2. **Grant** → allow the Health Connect data types you want, then a separate grant for
   **History** (data older than 30 days) and **Background** (scheduled export).
3. Tap **30d** / **90d** / **365d** / **All time** (or enter a custom range) for the historical
   snapshot.
4. Tap **Export now** for a manual delta, and pick **Daily**/**Weekly** for scheduled export.

---

## Output

### `snapshots/health-connect-2026-04-16_to_2026-09-14.ndjson`

NDJSON: a header line, one native record per line, then a manifest.

```json
{"schema":"vault-health-exporter/ndjson","version":1,"kind":"snapshot","exported_at":"…","source":"health-connect","requested_range":{"start":"…","end":"…","timezone":"Pacific/Auckland"},"change_token_before":"…","app":{"name":"Vault Health Exporter","version":"0.0.1"}}
{"id":"ce06d323-…","type":"ExerciseSession","source_package":"com.xiaomi.wearable","start_time":"…","end_time":"…","zone_offset":"+12:00","values":{"exerciseType":"running"},"stages":[],"route":{"state":"available","points":[{"t":"…","lat":-43.5212,"lon":172.5646}]}}
{"kind":"manifest","record_total":79677,"record_counts":{"Steps":13275,"…":0},"issue_counts":{"route_consent_required":14},"checksum":{"algorithm":"sha256","scope":"payload_excluding_manifest","value":"…"}}
```

- `manifest.checksum` covers the **payload** (header + records, excluding the manifest line).
- The sibling `.ndjson.sha256` covers the **whole file**: `<hex>  <filename>`.

### `deltas/health-connect-delta-2026-09-15T020000Z.ndjson`

Same shape; body lines are bare upsert records, and deletions are tombstones:

```json
{"op":"delete","id":"…","deleted_at":"2026-09-15T01:59:00Z"}
```

Route imports are written here too, as `health-connect-route-<timestamp>.ndjson`.

### `Summaries/2026-09-14.md`

```markdown
---
date: 2026-09-14
generated_by: vault-health-exporter
source: health-connect
---
# Health Summary — 2026-09-14

- Steps: 8432
- Distance: 6.21 km
- Exercise: 47 min (2 sessions)
- Sleep: 7h 12m (deep 1h 20m, REM 1h 05m)
- Heart rate: avg 62 bpm (min 48, max 141)
- Weight: 74.2 kg

## Workouts
- 2026-09-14 06:30–07:15 Run 45 min `id=…` `route=available`
```

Sleep is attributed to the day you woke up.

---

## Permissions

Requested on demand from the app (`Data types X/41` → **Grant**), plus two special grants
(`Read health data history`, `Read health data in background`) and `ACTIVITY_RECOGNITION`
for steps/distance.

Health Connect has no separate cadence permission: steps cadence is governed by `READ_STEPS`
and cycling cadence by `READ_EXERCISE`.

### Exercise routes (GPS)

Routes are only read in the foreground and written to their own immutable file; background work
records `consent_required`/`no_data` and never collects routes.

Where the data comes from matters:

- **Strava cannot help.** Its manifest declares `WRITE_DISTANCE`, `WRITE_EXERCISE`,
  `WRITE_TOTAL_CALORIES_BURNED` and `READ_WEIGHT` — there is **no `WRITE_EXERCISE_ROUTE`**, so it
  never writes GPS to Health Connect.
- **Mi Fitness can** (`WRITE_EXERCISE_ROUTE`). Enable its Health Connect sharing so routes land in
  Health Connect, then the app can read them.
- If Health Connect holds no route for a session, nothing can be exported for it; the manifest
  issue counters (`route_consent_required`, `route_no_data`) report this honestly.

---

## Reliability

- Immutable exports: temp-write → verify read-back → rename. **Never overwritten.**
- **No duplicates:** every export carries a content hash; re-running Import/Export with unchanged
  data writes nothing.
- Expired/invalid change tokens surface a clear "run a fresh snapshot" prompt — never guessed.
- WorkManager periodic work plus a boot/app-open catch-up job covers missed runs and reboots.

---

## Build from source

Requires JDK 17 and the Android SDK (platform 36, build-tools 36.0.0).

```bash
./gradlew :core:test            # pure-JVM unit tests, no device needed
./gradlew :app:assembleDebug    # debug-signed APK
./gradlew :app:assembleRelease  # release-signed APK (uses keystore.properties)
```

### Signing

`keystore.properties` (git-ignored) points at a keystore:

```properties
storeFile=../keystore/sideload.p12
storePassword=…
keyAlias=vaulthealth
keyPassword=…
```

Without it, `assembleRelease` produces an unsigned APK and only `assembleDebug` is signed.

---

## Layout

```
health-exporter/
├── core/   pure Kotlin/JVM — models, NDJSON codec, checksums, dedup, token policy, summaries
└── app/    Android — Health Connect, SAF writer, Room, DataStore, WorkManager, Compose UI
```

All deterministic logic lives in `:core` so it is unit-tested on the JVM.

---

## Known limitations

- Route/GPS availability depends entirely on what source apps write into Health Connect.
- `READ_EXERCISE_ROUTES` (bulk route access) is newer than connect-client 1.1.0, so the app
  cannot request it through the library's permission contract; route import still works when
  Health Connect already shares routes.
- No cloud sync, accounts, dashboards, or NAS-side importer — deliberately out of scope.

---

## Licence

Provided as-is for personal use.
