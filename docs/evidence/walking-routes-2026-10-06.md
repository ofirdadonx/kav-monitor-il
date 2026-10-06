# Walking routes vs straight line (2026-10-06)

Router: FOSSGIS OSRM, foot profile, `https://routing.openstreetmap.de/routed-foot/route/v1/foot/{lon},{lat};{lon},{lat}?overview=false`
(keyless, requests sent with the app User-Agent, one at a time). The table endpoint (`/table/v1/foot/...?sources=0&annotations=distance,duration`)
also works, but it isn't used yet. Straight-line estimate = the app's pre-v0.4 rule: distance × 1.3 ÷ 80 m/min, rounded up.

| from → stop | straight line | ×1.3 estimate | routed distance | routed time | route / straight | request |
|---|---|---|---|---|---|---|
| Savidor station entrance (32.0835,34.7990) → 20001 Savidor platforms A | 277 m | 5 min | 943 m | 13 min | ×3.41 | 0.84 s |
| Arlozorov/Namir corner (32.0847,34.7925) → 20001 | 379 m | 7 min | 652 m | 9 min | ×1.72 | 0.44 s |
| Near 36926 (31.9625,34.8283) → 36926 Shlomo Levi/Sha'ar Yafo | 55 m | 1 min | 89 m | 1 min | ×1.61 | 0.44 s |
| 1.2 km east of 36926 (31.962154,34.8406) → 36926 | 1200 m | 20 min | 2611 m | 35 min | ×2.18 | 0.45 s |
| Rishon, Rothschild/Herzl (31.9640,34.8040) → 36926 | 2262 m | 37 min | 2888 m | 39 min | ×1.28 | 0.44 s |
| Azrieli (32.0705,34.7905) → 25141 Begin/Nahmani | 1090 m | 18 min | 1174 m | 16 min | ×1.08 | 0.52 s |
| Rothschild/Allenby (32.0640,34.7740) → 25141 | 703 m | 12 min | 811 m | 11 min | ×1.15 | 0.43 s |

Second batch (recorded as test fixtures, `androidApp/src/test/resources/fixtures/osrm/`, ~0.45 s each):

| from → stop | routed distance | routed time |
|---|---|---|
| Savidor entrance → 20001 | 943 m | 12.6 min |
| Savidor entrance → 20762 Arlozorov/Al Parashat Drakhim | 693 m | 9.0 min |
| Savidor entrance → 21705 Midtown | 797 m | 10.7 min |
| near 36926 → 36926 / 33504 / 36073 | 89 / 350 / 766 m | 1.0 / 4.5 / 10.2 min |
| 1.2 km east → 36926 / 33504 / 36073 | 2611 / 2879 / 3048 m | 34.6 / 38.3 / 40.7 min |
| 1.5 km east → 36926 / 33504 / 36073 | 3016 / 3284 / 3453 m | 40.0 / 43.7 / 46.1 min |

Findings:

* Along straight streets the ×1.3 rule is close (×1.08–1.28). It fails badly where the stop is across a barrier: the
  Savidor platforms are reached around the station (×3.4), and east of 36926 the blocks force a detour (×2.2,
  35 min instead of the estimated 20). A time-to-leave alert based on the estimate would have come 15 min late there.
* The nearest stop by straight line isn't always the nearest on foot: from the Savidor entrance, 20001 is nearest by
  straight line (277 m) but 943 m on foot, while 20762 is 693 m on foot. Re-ranking the top 3 with one table call
  would fix this; it isn't implemented in v0.4.
* Latency is ~0.45 s per request (one outlier 0.84 s), well inside the 5 s timeout.
