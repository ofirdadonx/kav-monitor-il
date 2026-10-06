# kav-monitor-app (מעקב קו)

Personal Android app: pick an Israeli bus line + direction, and get a loud notification when the bus
**leaves its origin stop** or **reaches a stop you choose**. Each watch is started on demand and runs for a limited
time (default 60 min). The last 5 watch configurations are kept as one-tap "recents".

Status: v0.1 (first working version). Built and unit-tested on Linux; not yet run on a device or emulator.
Raw evidence: `docs/curlbus-poll-log-2026-10-06.txt` (poll log), `docs/live-smoke-*.txt` (smoke runs).

## Architecture

Kotlin Multiplatform + Compose Multiplatform. Only the Android target is enabled. The code is split so an iOS
target can be added later (`iosArm64()` / `iosSimulatorArm64()` in `shared/build.gradle.kts` + an iOS app shell).

```
shared/                      KMP library (com.android.kotlin.multiplatform.library + jvm target for tests)
  commonMain/
    model/                   Direction, RouteStop, LiveVisit, StopSnapshot, WatchSpec, Operators (display table)
    data/
      StaticDataSource       interface: directions(line, date), stops(direction)
      StrideStaticDataSource Open Bus Stride implementation (GTFS stop sequence, fallbacks below)
      LiveDataSource         interface: stopVisits(stopCode)   ← swap for our own proxy later
      CurlbusLiveDataSource  curlbus.app implementation + CurlbusParser
      RouteRepository        caches directions per (line, day) and stops per (line_ref, day)
    trigger/                 TriggerConfig (all thresholds), TriggerDetector (pure, stateful)
    watch/                   WatchEngine (poll loop, time limit, error handling), WatchState, WatchStateHolder
    recents/RecentsStore     last 5 WatchSpecs as JSON in multiplatform-settings
    ui/                      Compose UI (Home / New watch / Watch), AppController (state + actions)
    composeResources/values/strings.xml   all UI strings (Hebrew)
  androidMain/               OkHttp Ktor engine
  jvmMain/                   OkHttp Ktor engine + dev/LiveSmoke.kt (live smoke runner)
  jvmTest/                   unit tests + real API fixtures (resources/fixtures)
androidApp/                  com.android.application (AGP 9 built-in Kotlin)
  KavMonitorApp              builds AppGraph (manual DI)
  MainActivity               hosts the shared App(); POST_NOTIFICATIONS request; back handling
  WatchService               foreground service (type dataSync) running WatchEngine; wake lock; Stop action
  Notifications              channels, ongoing status notification, high-priority alert, "finished"
  res/values/strings.xml     notification strings (Hebrew)
```

UI is Hebrew-only and forced RTL (`LayoutDirection.Rtl`), `android:supportsRtl="true"`.

### Data sources

* **Open Bus Stride** (`https://open-bus-stride-api.hasadna.org.il`), static data only:
  * directions: `/gtfs_routes/list?route_short_name=194&date_from=D&date_to=D` (all operators; deduped by `line_ref`).
  * stops: `/gtfs_rides/list?gtfs_route_id=…&limit=1` → `/gtfs_ride_stops/list?gtfs_ride_ids=…&order_by=stop_sequence asc`
    (includes stop code, name, city, lat/lon).
  * **Quirk:** at ~10:00 today's `gtfs_ride_stops` were not loaded yet (empty), while today's routes/rides were.
    Fallback 1: same `line_ref` on up to 7 previous days (`/gtfs_routes/list?line_refs=…`). Fallback 2 (the
    prototype's approach): SIRI ride history (`/siri_rides`, `/siri_ride_stops`) + `/gtfs_stops` for names.
    The UI shows a small note when a previous day's schedule is used.
* **curlbus** (`https://curlbus.app/<stop_code>`, `Accept: application/json`), realtime only. One call per poll.
  Visits are filtered by `line_id` (== Stride `line_ref`), which separates Egged 194 (37121) from Kavim's 194.

## Trigger logic (and the live evidence behind it)

Investigated on 2026-10-06 09:50–10:19 by polling curlbus for Egged 194 (line_ref 37121) at the origin 20001
(Savidor), stop 2 (20762), stop 5 (21472 Azrieli), stop 13 (36926) every ~37 s. What the fields really mean:

| field | meaning (observed) |
|---|---|
| `departed` | the trip's **planned departure time from its origin** (same value at every stop). Not a "has departed" flag. |
| `eta` | predicted arrival at this stop, minute granularity. At the origin before the trip starts `eta == departed`. |
| `timestamp` | when the SIRI record was recorded. For trips not started yet it's hours old (e.g. 05:55 for a 10:02 trip). |
| `location` | `null` until the trip is live (journey monitored); then a fix (lat/lon **as strings**) every ~15–70 s. |
| top-level `timestamp` | unreliable (sometimes the fetch time, sometimes an old cached value, two different formats). Not used. |

Key observation (10:02 departure, vehicle 18379504): it was listed at 20001 from at least 09:50 with no GPS;
at **10:01:42 it dropped off the origin's list** (its first GPS fix, 10:00:31, was ~50 m from the platform);
at 10:02:07 the fix was 4 m from the stop and at 10:03:11 ~77 m away and moving. In the same poll in which it dropped,
the *next* trip (10:32) was added to the list. The 10:17 trip (vehicle 7628169) did the same: first fix 10:15:22 at the
platform, dropped off the origin list between 10:15:28 and 10:16:04 (the shared `WatchEngine`, run live via
`liveSmoke`, alerted at 10:16:18), fix at the platform 10:17:04, ~60 m away 10:17:57, ~135 m 10:18:50. curlbus lists only the next ~2 trips per line, so the prototype's
"new vehicle/trip appeared at origin" rule effectively fires when the previous bus leaves, but reports the wrong
(next) bus, and on its first poll it treats buses that are only *scheduled* as new.

**Departs origin** (poll the origin stop only), per trip, fires at most once:
1. `LEFT_ORIGIN_DROPPED`: the trip was listed at the origin and is gone in a later *reliable* poll, and
   `now >= scheduled departure - 3 min` (`departEarlyTolerance`). This is the main signal. In practice it fires
   when the trip goes live at the platform, so it is typically **~1 min before the bus physically pulls out**
   (≈50 s and ≈70 s early in the two observed departures). A GPS-confirmed "moving" signal would come
   1–2 min *after* departure (fixes are ~1/min), so for a "start walking" cue the drop is the better signal.
2. `LEFT_ORIGIN_SCHEDULED`: the trip dropped off *more* than 3 min early (driver activated it early). It's held
   and fires when its scheduled departure time is reached (it reappearing cancels this).
3. `LEFT_ORIGIN_GPS`: still listed, but with a fresh fix (≤ 3 min old) ≥ 300 m from the origin, past the window.
   Not seen live (live trips drop off the origin list). It's a safety net.
* A newly *appearing* trip never fires.

**Arrives at stop X** (poll stop X only):
1. `AT_STOP_GPS`: fresh fix within **150 m** of the stop (`arriveRadiusMeters`). 150 m rather than 200 m so that
   stop 2 (180 m from Savidor) doesn't fire while the bus is still standing at the origin.
2. `AT_STOP_ETA`: trip is live (fresh fix) **and** `eta - now <= 1 min` **and** fix ≤ 1000 m from the stop.
   ETAs slide in traffic (Azrieli: 10:09 → 10:10 → 10:11 while the bus crawled ~900 m away), hence the distance guard.
   An ETA without GPS (trip not started) never fires.
3. `DROPPED_AT_STOP`: the trip drops off the stop's list. SIRI removes a visit when the bus is at/near the stop,
   so for a live trip this means "arrived / just passed". A never-live trip counts only if its ETA was within 3 min.
* Live examples (replayed in `TriggerReplayTest`): stop 36926: ETA rule fired at 10:07:17 (fix ~600 m, ETA 10:08), and
  the trip dropped at 10:07:54. Azrieli: ETA rule fired at 10:10:27, and the trip dropped at 10:11:03.

**Reliability:** a poll counts for "dropped" decisions only if it succeeded, had no `errors`, and the stop returned at
least one visit of any line, so an empty or broken response is never read as "the bus left". HTTP errors are shown in
the status and polling continues. GPS fixes older than 3 min are ignored.

All thresholds are in `shared/.../trigger/TriggerConfig.kt`. Poll interval: 35 s.

## Background behaviour (Android)

* `WatchService` is a foreground service of type `dataSync` (`FOREGROUND_SERVICE_DATA_SYNC` permission for
  Android 14+), started from the activity. `onTimeout` (Android 15 dataSync budget) stops cleanly.
* On Android 13+ the app asks for `POST_NOTIFICATIONS` when you start a watch. The watch also runs if you deny it,
  but then no alerts are shown.
* Ongoing notification (low-importance channel): line, trigger, last update time, buses seen, end time, errors,
  and a **Stop** action. Alert (high-importance channel `bus_alert_v1`): default notification sound + vibration pattern.
* A time-limited partial wake lock (watch duration + 2 min) keeps the poll timer running with the screen off.
  It's released on stop/timeout.
* The watch keeps running after an alert (the next bus can alert too) until stopped or the time limit.

## Build & install

Requirements: JDK 17+ (built with OpenJDK 21), Android SDK with `platforms;android-37.0` and build-tools
(`ANDROID_HOME` or `local.properties` with `sdk.dir=…`).

```bash
./gradlew assembleDebug                 # → androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :shared:jvmTest               # unit tests (fixtures + trigger logic + engine)
./gradlew :shared:liveSmoke             # live: Stride directions/stops + curlbus visits for line 194
./gradlew :shared:liveSmoke -PstopCode=20001 -PwatchMinutes=10   # run the real WatchEngine against curlbus

adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Sideloading without adb: copy the APK to the phone and open it (allow "install unknown apps" for the file manager).
Debug build, signed with the local debug key.

## Known limitations / risks

* curlbus is community-run and has no documented rate limits. We make one call per 35 s per active watch.
  Some stops always return HTTP 500 (e.g. 27117 on line 194). The error shows in the status and polling continues.
* Stride is slow-ish (≈1 s per call) and today's GTFS ride stops can be missing (see fallback above).
* GPS fixes are 15–70 s apart and lag 10–60 s, so arrival alerts are roughly ±1 min.
* Not tested on a device yet: notification sound/vibration, Doze and OEM battery savers, and FGS behaviour.
