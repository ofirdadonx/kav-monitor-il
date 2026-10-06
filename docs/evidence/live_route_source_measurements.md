# Live route view: data source measurements (2026-10-06, line 194 Egged, line_ref 37121)

## Stride `/siri_vehicle_locations/list`
* `?siri_routes__line_ref=37121&recorded_at_time_from=<now-15min>&order_by=recorded_at_time desc` (14:49 IL):
  **HTTP 500 after 61 s**: `psycopg2.errors.QueryCanceled: canceling statement due to statement timeout`.
* Two-step alternative works and is fast: `/siri_rides/list?siri_route__line_refs=37121&scheduled_start_time_from=…` (~1 s)
  then `/siri_vehicle_locations/list?siri_rides__ids=…&order_by=recorded_at_time desc` (~1.3–1.8 s).
* **Lag** (newest data available vs. wall clock):
  * 11:50 UTC (14:50 IL): newest vehicle location for the line recorded 11:24:31 UTC → **~26 min old**; with
    `recorded_at_time_from=now-20min` the query returned **0 rows**. Newest loaded SIRI snapshot `2026/10/06/11/25`,
    its ETL finished 11:49:04 UTC.
  * 12:00 UTC (15:00 IL): newest location 11:44:06 UTC → **~16 min old**; snapshot `11/44` loaded at 12:00:06 UTC.
  * Trips that started after the newest loaded snapshot are missing entirely from `siri_rides`.
* Verdict: good for history, unusable for a "where is the bus now" view (15–26 min behind).

## curlbus.app/<stop> (MOT SIRI StopMonitoring proxy)
Full scan of all 28 stops of 37121 at 14:51:31–14:52:19 IL (one-off, for this measurement only):
see `shared/src/jvmTest/resources/fixtures/route194_1451/SCAN_SUMMARY.txt` (raw responses in the same folder).
* **GPS lag** (fetch time − visit `timestamp`): 13–49 s for every live bus.
* **Horizon**: the largest ETA listed at any stop was 21–29 min → a stop lists only buses ≤ ~30 min away.
* A live bus is listed at every stop ahead of it within that horizon, with the same GPS fix.
* Not-started trips are listed (no `location`, `timestamp` hours old) at the origin and nearby stops.
* 4 of 28 stops (20876, 20725, 25141, 27117) answered **HTTP 500** ("Server got itself in trouble") – per stop, repeatable.
* SIRI listed one trip twice with two vehicles (trip 585301551, vehicles 23400602/23401402) at 20001.

## Chosen design
Sample a few stops so that consecutive samples are ≤ 15 min of scheduled run time apart (+ origin + last stop + the
target stop), e.g. 194 → #1 20001, #9 27117, #12 33117, #21 32939, #28 36053 = **5 requests per refresh**, every 45 s,
only while the screen is visible. A failing stop is replaced by a neighbour (remembered). Progress = GPS projected onto
the stop-to-stop polyline between the last sampled stop already passed and the first sampled stop still listing the bus,
interpolating GTFS `shape_dist_traveled` (route length 25 510 m for 37121).

Live run: `docs/evidence/route_live_smoke_194.txt` (`./gradlew :shared:routeLiveSmoke`), 14:58–15:00 IL:
first refresh 7 requests (27117 → 500, neighbour used), then 5; 3 active buses at 97 % / 51 % / 16 %, GPS 19–56 s old,
off-route distance 3–263 m.
