# v0.5: one multi-stop curlbus request per refresh: measurements (2026-10-06, Egged 194 / line_ref 37121)

All numbers below come from live runs from this Linux box, not from a phone.
Raw logs:
* `multistop-raw-log-2026-10-06.txt`: Python urllib with `Accept-Encoding: gzip`, 19:30–19:41 IL.
* `route_live_smoke_194_v05.txt`: the app's own Kotlin client via `./gradlew :shared:routeLiveSmoke -Pdirs=1`, 19:41 and 19:44.

## Full route in one request (`/<24 stops joined by +>`)

The request covers the 24 working stops of 194. The four stops that always answer HTTP 500 are left out: 20876, 20725, 25141, 27117.

| | value |
|---|---|
| latency (11 rounds, 1/min, 19:30:57–19:40:47) | **1.59–2.33 s** (median ≈ 1.8 s) |
| app client (Ktor/OkHttp), 2 runs | 1.76 s and 1.92 s, 316–323 visits |
| body (JSON) | **341–375 KB** (all lines at those stops, with `static_info` / `stops_info`) |
| on the wire (gzip) | **20.6–23.0 KB** |
| all 28 stops (with the broken ones) | **HTTP 500 in 0.95 s** (the whole request fails) |
| single stop, for comparison | 20001: 0.69–0.78 s, 25.5 KB (3.2 KB gzip); 31492: 0.59–0.94 s, 10 KB (1.6 KB gzip) |

* gzip: OkHttp asks for it and decodes it transparently. This is checked by `HttpGzipTest`, which runs the app's real client against a local server. At one request per minute, that is about 1.3 MB/hour on the wire while the route view is open.
* The user saw 9.2 s for 24 stops. I measured about 1.8 s. The difference may be server load or cache warmth at curlbus (`CACHE_TTL` 30 s). The multi-stop timeout is 30 s.

## Bisect on HTTP 500 (live, the app's `MultiStopFetcher`)

The first refresh with an empty broken-stop cache sends 28 stops and gets HTTP 500. It is followed by a bisect:

* **18 requests, 5.9 s** in total. The halves that answer are kept, so the first refresh already covers all 24 working stops.
* It finds all 4 broken stops: 20876, 20725, 25141, 27117.
* An earlier version re-asked a stop that had already failed on its own, which took 22 requests and 6.6 s (19:41 run). It was fixed: a stop that fails in its own single-stop request is broken. A stop whose failure was only inferred (the other half answered) is confirmed with one single-stop request. If that request succeeds, the 500 was transient and nothing is marked.
* The broken stops go into the persistent `BrokenStops` cache (TTL 6 h). From then on it is **1 request per refresh**: 1.76 s and 1.92 s live.
* In tests (`MultiStopFetcherTest`):
  * one culprit among 28 stops costs at most 2 + 2·⌈log2 28⌉ = 12 requests;
  * a transient 500 marks nothing;
  * more than 30 stops → even chunks (34 → 17 + 17);
  * after the TTL the stops are tried again.

## Coverage: sampled (v0.4) vs full request (v0.5) at the same moment

`FullRouteAggregationTest.coverageSampledVersusFullOnTheSameMoment` uses the 11 recorded answers. The sampled stops get exactly their slice of the same answer.

| | sampled (5–7 stops) | full (24 stops) |
|---|---|---|
| buses found (sum over 11 rounds) | 32 | 32 |
| buses missed | 0 | 0 |
| stop ETAs per bus (sum) | 59 | **266** |

The live run at 19:41/19:44 gave the same picture: 2 active + 1 not started in both modes, nothing missing. Evening traffic was light: 2–3 trips of 194 at a time. Sampling did not miss a bus here. The gains of the full request are:
* **one request instead of 5–7**;
* an ETA at **every** stop ahead (follow-bus and stop ETAs come straight from curlbus);
* positions bounded by adjacent stops instead of samples ≤ 15 min apart.

## Watch: adding the next 1–2 stops? Not adopted

A bus is listed at every stop ahead of it, so a downstream listing can't tell "passed the target" from "about to arrive". In the 25141 fallback recording, trip 23399602 dropped off 20750 at about the time it passed (fix 18:39:32 at 2573 m vs stop 2600 m; 18:40:29 at 2624 m), while 31492 kept listing it throughout. The target-stop disappearance rule plus GPS projection already decides departs/arrives, and the extra stops would only add bytes. The watch keeps 1 request per poll: the target, or the target + stand-ins in one multi-stop request when the target is broken.

## `/nearby`

* 0.44–0.62 s per request.
* Results by place:
  * Savidor r300: 35 stops (15.8 KB, 1.2 KB gzip);
  * Azrieli r300: 11 stops;
  * near 36926: 3 stops;
  * (31.99, 34.77) r300: 5 stops, r1000: 61 stops.
* The answer is unsorted; the app sorts by `distance`.
* Cached per ~100 m cell (3 decimals).

## Stop screen (`/21472`, Azrieli)

0.78–0.88 s, 105 KB JSON, 87 visits (all lines). In the app, refreshes are 1 request per 60 s, only while the screen is shown.
