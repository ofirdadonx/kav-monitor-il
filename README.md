# kav-monitor-app (מעקב קו)

Personal Android app: pick an Israeli bus line + direction, and get a loud notification when the bus
**leaves its origin stop**, **reaches a stop you choose**, or when it's **time to leave** to catch it at your stop. Each watch is started on demand and runs for a limited
time (default 60 min). The last 5 watch configurations are kept as one-tap "recents".

Status: **v0.3** (beta). v0.1 and v0.2-beta were confirmed working on the user's phone. v0.3 was built and verified on
Linux only (unit tests, Robolectric/Roborazzi UI tests, live API runs). It has not been run on a device or emulator yet,
so real GPS fixes and the real system permission dialog are untested.
Raw evidence: `docs/curlbus-poll-log-2026-10-06.txt` (poll log), `docs/live-smoke-*.txt` (smoke runs),
`docs/evidence/` (v0.2 live route source measurements, v0.3 Stride geo probe and "time to leave" live run).

### What's new in v0.3.1 (broken-stop fallback)

Some stops answer **HTTP 500** on curlbus for every request (probed 2026-10-06 17:57 IL: 25141, 27117, 20876 and
20725 on line 194; Accept json / text/plain / */*, `?lang=he`, a trailing slash, `?format=json` and multi-stop
`25141+21472` all return 500, `.json` 404, so there is no workaround on the curlbus side).

* **Detection.** A stop is "broken" after **2 consecutive 5xx** (`BrokenStopTracker`, `WatchEngine`); this is kept in
  settings with a **6 h TTL** (`BrokenStops`) and cleared on the first success.
* **Fallback.** A watch on a broken stop polls the nearest *working* neighbours instead, **at most 2 calls per poll**:
  the nearest earlier stop (upstream) and the nearest later stop (downstream). A stand-in that answers 5xx itself is
  replaced on the next poll, 5 s later.
  * **Departs-origin** uses the next working stop only (the bus leaving the origin shows up there).
  * **Arrives / time-to-leave** build the target's ETA from the upstream ETA plus the scheduled GTFS travel time
    (upstream→target); once the bus has left the upstream list, they use the downstream ETA minus the travel time
    (target→downstream). Without GTFS times they fall back to distance along the route at 5 m/s. The freshest GPS fix
    is kept. A bus whose fix, projected on the route, is more than 150 m past the target is treated as passed. This is
    route-projected because straight lines are misleading on curvy legs.
* **UI.** The watch screen and notification say `התחנה לא מדווחת ב-curlbus — מחשב לפי תחנת <name> (הערכה)`, derived
  ETAs are marked `משוער לפי תחנה סמוכה`, and alerts carry a `הערכה` line. Broken stops stay selectable, with a
  `הערכה` badge in the stop picker and the nearest-stop panel.
* **Errors.** No raw exception text anywhere. Errors are short Hebrew messages (server / stop-specific server /
  network / timeout / source / other), and the raw details go to logcat (`KavMonitor`). While retrying, the screen
  and notification show `ממשיך לנסות · עדכון אחרון שהצליח: HH:MM`.
* **Tests.** `StopFallbackTest` (jvm) covers the plan, travel times, ETA derivation, TTL persistence, the
  2-error threshold, the engine switch and departs-via-next-stop. `FallbackUiTest` (Robolectric) replays recorded
  curlbus answers (2026-10-06 from 17:58 IL, `fixtures/fallback_25141`) with 25141 and 27117 really answering 500.
  It checks the friendly error, the switch to 20750 + 31492, that no false arrival fires for bus 7687869 (it passed
  25141 at ~17:57:40, before the fallback could start), that Stop leaves zero requests, and the picker badge.
  Screenshots are in `docs/screenshots/v0.3.1/`.

### What's new in v0.3

* **"Time to leave" trigger (זמן לצאת)**, the third option next to departs/arrives. You pick a boarding stop
  (it defaults to the route stop nearest to you and can be changed in the stop list, origin included). The watch
  polls **only that stop** on curlbus (one call per 35 s poll). For every bus:
  `leaveAt = ETA at the boarding stop − walk minutes − buffer`. When `now ≥ leaveAt` for the next bus you can still
  catch, a high-priority alert fires: "צא עכשיו — 194 מגיע ל<stop> ב-HH:mm".
  * **Walk time** = straight-line distance × **1.3** (detour factor) ÷ **80 m/min**, rounded up, clamped to
    0–60 min, plus a **1 min** buffer. Before starting you see the calculation and can change it with −/+ (then it's
    "manual" and no longer follows your location). Without a location fix the default is 5 min.
    Constants: `TriggerConfig.kt` (`WALK_DETOUR_FACTOR`, `WALK_SPEED_M_PER_MIN`, `LEAVE_BUFFER_MINUTES`, …).
  * Location is taken when the screen opens and refreshed only while the app is visible (watch screen included).
    The background service never reads location. It uses the last walk time the UI handed it (`LeavePlanHolder`).
  * **ETAs** are the live curlbus ETAs, re-evaluated on every poll. If the trip hasn't started (no GPS) the ETA is
    marked "לפי לוח הזמנים" (scheduled) in the UI and the alert; live ones are marked "חי (GPS)".
  * **No spam**: each bus alerts at most once, and alerts are at least 3 min apart (`leaveMinAlertGap`). If the ETA
    moves later after an alert, nothing happens. If it moves earlier, a bus that hasn't alerted yet fires as soon as
    its new leaveAt passes. Side effect of the gap: when two buses come within ~3 min of each other, usually only the
    first one alerts (the second is shown on the watch screen).
  * **Not catchable**: a bus whose leaveAt is more than 1 min in the past (`leaveGrace`) is skipped and shown as
    "לא ניתן להספיק (היה צריך לצאת ב-HH:mm)". A bus already in the past when first seen never alerts.
  * Watch screen: per bus "צאו ב-HH:mm (בעוד X דק׳)" / "צאו עכשיו!" / "לא ניתן להספיק", with the ETA and its
    live/scheduled label. Ongoing notification: next leave time with a **countdown chronometer**.
  * **Live check** (2026-10-06, stop 36926 Shlomo Levi/Sha'ar Yafo, #13 on Egged 194, walk 6 + buffer 1 = 7 min lead,
    `leaveLiveSmoke`, log `docs/evidence/leave_live_36926_2026-10-06.txt`, replayed in `LeaveReplayTest`):
    two alerts, both before the bus. Bus 23401502: alert 16:51:00 (ETA then 16:58), the walker would be at the stop
    at 16:57, and the bus dropped off the stop's list between 16:59:52 and 17:00:28 (~3 min wait, the ETA slid 2 min
    after the alert). Bus 23459302: alert 17:23:28 (ETA 17:30), at the stop 17:29:28, bus gone between 17:30:33 and
    17:31:09 (~1–1.5 min wait). A bus first seen with leaveAt 1.5 min in the past was correctly marked not catchable.
    No duplicate alerts as ETAs moved. "Arrival" is inferred from the bus dropping off the stop's list (±~35–60 s).
* **Nearest stop on the route** ("התחנה הקרובה אליך במסלול") after choosing a direction, and on the watch screen:
  stop name, city · stop code, and the distance labelled **"בקו אווירי"** (whole meters below 1000 m, else km with one
  decimal). Haversine distance, stops without coordinates are skipped. One tap makes it the alert stop: at the origin
  it sets "departs", otherwise "arrives at this stop"; in "time to leave" mode it becomes the boarding stop. On the
  watch screen it also shows the straight-line distance to the watched stop.
* **Location permission** (FINE + COARSE, foreground only, no `ACCESS_BACKGROUND_LOCATION`): the system dialog is
  requested once, automatically, the first time the section is shown, under a short Hebrew rationale. After a denial
  the section shows a small "אין גישה למיקום · הפעלת מיקום" row and never asks again by itself. Tapping
  "הפעלת מיקום" asks again (or opens the app's settings page if Android no longer shows the dialog).
  Updates: `LocationManager` (fused provider on Android 12+, else GPS + network; no Play Services), every 20 s or
  15 m. They run only while the section is on screen **and** the app is visible, and stop when you leave the screen.
  Behind the common `LocationProvider` interface (an iOS implementation can be added).
* **Observed departures**: when the app actually saw a bus leave its origin, its card shows
  "יצא בפועל ~HH:mm (+N דק׳ מהמתוכנן)". This only appears when observed: the live route view saw it at the origin
  ≤ 150 s before seeing it live away from it, or a departs-origin watch detected the departure (drop-off/GPS rules,
  not the "scheduled" fallback).
* **Home-screen "nearest stops overall": skipped.** Stride has no lat/lon query (`/gtfs_stops/list` filters only by
  code/city; Tel Aviv alone has 1046 stops; a `siri_ride_stops` bounding-box query returned nothing in 1.8–9.7 s).
  Evidence: `docs/evidence/stride_geo_query_probe.md`.

### What's new in v0.2

* **Readable direction cards**: operator, origin (stop + city), destination (stop + city), number of stops.
  `route_long_name` is parsed (`RouteName`); the real first/last stop of the stop list is preferred once loaded.
  Stop lists for all cards are fetched in parallel (max 3 at a time) and cached. The card shows immediately and the
  count fills in ("סופר…" until then). Doubled apostrophes in MOT names are shown as gershayim (`חב''ד` → `חב"ד`).
* **Live route view** ("על הקו עכשיו") after choosing a direction, and on the watch screen while the watch runs:
  number of active buses, and per bus the % of route with a progress bar. See *Live route view* below.
* **Per-bus details**: vehicle number, scheduled departure from the origin, last update ("לפני X" + clock time) with
  GPS freshness (עדכני ≤ 2 min / לא עדכני ≤ 5 min / ישן), % of route + km left, next/nearby stop, ETA to the chosen
  stop and to the last stop (curlbus ETAs, shown only when the stop was actually sampled for that bus).
  Not-started trips: "טרם יצא · יציאה מתוכננת HH:mm". At the origin the ETA is labelled "יציאה משוערת".
  Not shown on purpose: delay vs. schedule (the stop list may come from another day's representative trip, so a
  computed delay could be wrong), speed and heading (one fix per ~minute is too noisy).
* **Stop really stops** (in-app button, notification action, time limit, Android `onTimeout`): see *Background behaviour*.
* UI: bidi isolation of stop names in the recents subtitle and status texts; sticky bottom "Start" bar; local date
  format (`05/10`); small version label at the bottom of the home screen (`גרסה v0.2-beta`).
* Bug fix: SIRI sometimes lists the same trip twice with two vehicles (seen live: trip 585301551 at 20001). v0.1
  used the trip as a `LazyColumn` key, so that could crash the watch screen. Buses are now de-duplicated per key.

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
    trigger/                 TriggerConfig (all thresholds), TriggerDetector (pure, stateful), LeaveTime (walk time,
                             leaveAt, catchable)
    location/                LocationProvider (interface), LocationController (permission + visible-only updates),
                             NearestStops (haversine, formatting)
    route/                   live route view: RouteSampling (which stops to poll), RouteGeometry (GPS → % of route),
                             RouteLiveFetcher (one refresh), RouteLiveController (visible-only refresh loop)
    watch/                   WatchEngine (poll loop, time limit, error handling), WatchState, WatchStateHolder,
                             LeavePlanHolder (walk time handed from the UI to the service)
    recents/RecentsStore     last 5 WatchSpecs as JSON in multiplatform-settings
    ui/                      Compose UI (Home / New watch / Watch), AppController (state + actions),
                             BusDetails (per-bus card), RouteLivePanel (route view), NearestStopPanel
    composeResources/values/strings.xml   all UI strings (Hebrew)
  androidMain/               OkHttp Ktor engine
  jvmMain/                   OkHttp Ktor engine + dev/LiveSmoke.kt, dev/RouteLiveSmoke.kt, dev/LeaveLiveSmoke.kt (live runners)
  jvmTest/                   unit tests + real API fixtures (resources/fixtures)
androidApp/                  com.android.application (AGP 9 built-in Kotlin)
  KavMonitorApp              builds AppGraph (manual DI)
  MainActivity               hosts the shared App(); POST_NOTIFICATIONS + location permission requests; back handling
  AndroidLocationProvider    LocationManager-based LocationProvider (foreground only)
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
  Requests send `User-Agent: kav-monitor-app/0.3`.

### Live route view: data source (measured 2026-10-06, Egged 194 / line_ref 37121)

Full numbers: `docs/evidence/live_route_source_measurements.md`, live run: `docs/evidence/route_live_smoke_194.txt`.

| | Stride `siri_vehicle_locations` | curlbus per stop (chosen) |
|---|---|---|
| query by line | `?siri_routes__line_ref=` → **HTTP 500 after 61 s** (statement timeout). The 2-step query (rides → locations) takes ~1–2 s | one request per stop |
| lag | **15–26 min** (newest location 11:24:31 UTC at 11:50 UTC; 11:44:06 at 12:00). Trips started after the last ETL snapshot are missing | **13–49 s** GPS age at fetch |
| coverage | all rides of the line | each stop lists buses ≤ ~30 min (21–29 min max ETA) away, and a live bus is listed at every stop ahead of it |

Stride is fine for history but useless for "where is the bus now". A one-off scan of all 28 stops showed that
polling a few well-spaced stops is enough:

* **Sampling** (`RouteSampling`): origin, last stop, and enough stops in between that consecutive samples are
  ≤ 15 min of scheduled run time apart (≤ 6 km if times are missing), plus the stop you are watching.
  For 194: #1 20001, #9 27117, #12 33117, #21 32939, #28 36053 = **5 requests per refresh**, at most 3 in parallel.
* Some stops always answer HTTP 500 (20876, 20725, 25141, 27117 on 194). A failed sample is replaced by a neighbour
  and the substitute is remembered (first refresh 7 requests, then 5).
* **Progress**: the bus's GPS fix is projected onto the stop-to-stop polyline between the last sampled stop it has
  passed and the first sampled stop that still lists it, then interpolated on GTFS `shape_dist_traveled`
  (straight-line distances if missing). More than 2 km off the polyline → no % shown. "ליד התחנה" = within 150 m.
* **Politeness**: refresh every **45 s**, only while the route view is on screen **and** the app is visible
  (`onStart`/`onStop`). It stops when you leave the screen, change line, start a watch from the new-watch screen
  (the watch screen takes over), or stop the watch. The background service never fetches the route view; it keeps
  polling only the one watched stop every 35 s. Stop lists are cached per (line_ref, day), and parallel requests for
  the same list are shared.

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
* **Stopping** (in-app "עצור מעקב", notification "עצור", time limit, system `onTimeout`) goes through one path:
  the run's generation counter is bumped (late results of a cancelled poll are dropped), the poll job is cancelled,
  the wake lock released, the foreground notification removed (`STOP_FOREGROUND_REMOVE` + explicit cancel) and the
  service stops itself. Starting a new watch replaces the poller (never two pollers). Verified by
  `WatchServiceStopTest` and `AppUiTest` with request-counting fakes (zero HTTP requests after Stop).

## Variants (product flavor dimension `channel`)

| variant | applicationId | versionName | launcher label | icon |
|---|---|---|---|---|
| **beta** | `il.kavmonitor.app.beta` | `0.3-beta` | "מעקב קו בטא" | orange background, smaller bus + white "BETA" badge |
| **prod** | `il.kavmonitor.app` | `0.3` | "מעקב קו" | normal icon (green background, bus) |

The beta has its own applicationId, so it installs **next to** an existing v0.1 / prod install (`il.kavmonitor.app`)
instead of replacing it. Each has its own recents and notifications. The full version is shown at the bottom of the
home screen. APKs are copied under a versioned name: `androidApp/build/outputs/named/kav-monitor-<versionName>.apk`
(e.g. `kav-monitor-0.3-beta.apk`; release builds get `-release-unsigned`).

## Build & install

Requirements: JDK 17+ (built with OpenJDK 21), Android SDK with `platforms;android-37.0` and build-tools
(`ANDROID_HOME` or `local.properties` with `sdk.dir=…`).

```bash
./gradlew :androidApp:assembleBetaDebug   # → androidApp/build/outputs/named/kav-monitor-0.3-beta.apk
./gradlew :androidApp:assembleProdDebug   # → androidApp/build/outputs/named/kav-monitor-0.3.apk
./gradlew :shared:jvmTest                 # unit tests (fixtures, trigger logic, engine, route view, walk/leave time,
                                          # nearest stop, location controller, LeaveReplayTest = recorded live leave run)
./gradlew :shared:liveSmoke               # live: Stride directions/stops + curlbus visits for line 194
./gradlew :shared:liveSmoke -PstopCode=20001 -PwatchMinutes=10   # run the real WatchEngine against curlbus
./gradlew :shared:routeLiveSmoke [-Pline=194 -Poperator=3 -Prefreshes=2]   # live route view refreshes
./gradlew :shared:leaveLiveSmoke -PstopCode=36926 -PwalkMinutes=6 -Pminutes=60 [-PoutDir=/tmp/leave]   # live "time to leave" run

adb install -r androidApp/build/outputs/named/kav-monitor-0.3-beta.apk
```

Sideloading without adb: copy the APK to the phone and open it (allow "install unknown apps" for the file manager).
Debug build, signed with the local debug key.

## Known limitations / risks

* curlbus is community-run and has no documented rate limits. We make one call per 35 s per active watch, plus
  ~5 calls per 45 s while the live route view is on screen.
  Some stops always return HTTP 500 (e.g. 27117 on line 194). The error shows in the status and polling continues.
  If you *watch* such a stop, there's no neighbour fallback (the trigger needs that exact stop).
* Route view %: the stop-to-stop polyline is a straight chord between stops, so on long curvy legs the % can be off
  by up to ~0.5 km worth. Buses not yet listed at any sampled stop (≥ ~30 min before reaching one) aren't shown.
* The stop list comes from one representative GTFS ride (today's, or the previous day's when today's isn't loaded
  in Stride yet, which was the case all day on 2026-10-06). Alternatives that skip stops may show slightly wrong %.
* Stride is slow-ish (≈1 s per call) and today's GTFS ride stops can be missing (see fallback above).
* GPS fixes are 15–70 s apart and lag 10–60 s, so arrival alerts are roughly ±1 min.
* v0.1 and v0.2-beta ran fine on the user's phone. v0.3 hasn't been on a device yet: re-check the location
  permission dialog, real GPS fixes, the countdown in the notification, and the "time to leave" alert.
* "Time to leave" walk time is straight-line distance × 1.3, not a walking route. Long detours (highways, rail lines)
  can make it too short; use −/+ to correct it. The walk time is frozen at its last value while the app is in the
  background (no background location by design).
* "Time to leave" for a trip that hasn't started relies on the scheduled ETA, which can shift by several minutes
  when the bus goes live. It is re-evaluated each poll, but an alert that already went out isn't repeated.

## Emulator & UI tests

* `scripts/emulator.sh start|stop|status|install|shot NAME` – headless AVD `kav_pixel_api36`
  (Pixel 8, API 36 Google APIs x86_64, TZ Asia/Jerusalem). **On the current dev box the emulator cannot
  run**: the microVM's KVM hits a kernel BUG (`kvm_spurious_fault` in `vmx_vcpu_create`) on vCPU
  creation, leaving a hung qemu. The script refuses to start when that BUG is in dmesg (`FORCE=1` overrides).
  It should work on a host with healthy KVM.
* JVM UI tests (Robolectric + Roborazzi, real MainActivity/WatchService/notifications), `:androidApp:testBetaDebugUnitTest`:
  * `AppUiTest` offline: replays recorded real data (194 departure from 20001, a full 28-stop route scan at 14:51)
    → `docs/screenshots/v0.2/replay/`. With `-Pkav.live=true [-Pkav.liveWaitMin=N]` it runs the same flow against
    live Stride/curlbus, optionally waiting N minutes for a real alert → `docs/screenshots/v0.2/`.
    v0.1 screenshots stay in `docs/screenshots/` and `docs/screenshots/live/`.
  * `WatchServiceStopTest`: notification Stop action, Stop during an in-flight request, time limit, second start
    replaces the poller, system `onTimeout`. All checked with request counters, wake lock and notification state.
  * `RouteLiveLifecycleTest`: route view refreshes only while resumed/visible; nothing after back/stop.
  * v0.2 live screenshots (2026-10-06 15:34–15:59, real departure alert at 15:58:07) are in `docs/screenshots/v0.2/`.
    `beta_launcher_icon_preview.png` there is an SVG re-draw of the beta adaptive icon (circle mask), not a device capture.
  * `NearestLeaveUiTest` (v0.3) → `docs/screenshots/v0.3/01…09`, log `nearest_leave_log.txt`: mocked location
    (Robolectric `ShadowLocationManager`) near Savidor and near mid-route stop 36926, one-tap "arrives at this
    stop", "time to leave" walk time from location (1.2 km → 20 min) and manual ±, the watch screen with leave times
    (replayed recorded data at 36926, time-shifted), the ongoing notification countdown, the "צא עכשיו" alert after
    the user moves away (walk 25 min), and Stop (no HTTP, no location listeners afterwards). A second test covers
    the permission path: rationale, denial → quiet row, no second automatic request after leaving and returning,
    the explicit "הפעלת מיקום" request, and updates stopping in the background and when the screen is left.
    `AppUiTest` screenshots for v0.3 are in `docs/screenshots/v0.3/flow_replay/`.
  * Not covered: the real system permission dialog (Robolectric records the request; the test delivers the result),
    real GPS, Doze, the real notification shade.
