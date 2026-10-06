# Can Stride answer "stops near lat/lon" cheaply? (probe, 2026-10-06, v0.3)

Goal: decide whether the home screen can show "nearest stops overall" (any line) without a big download.

| query | result |
|---|---|
| `/gtfs_stops/list` | Filters are only `limit`, `offset`, `get_count`, `date_from`, `date_to`, `code`, `city`. **No lat/lon or bounding-box filter.** |
| `/gtfs_stops/list?city=תל אביב יפו&date_from=D&date_to=D&get_count=true` | **1046** stops for Tel Aviv alone. Filtering by city means downloading and scanning the whole city (and knowing the city first). |
| `/siri_ride_stops/list` with `gtfs_stop__lat__greater_or_equal/…lower_or_equal` + `gtfs_stop__lon__…` bbox (~300 m) and `gtfs_stop__date` | `[]` after **1.8 s** |
| same bbox + `siri_ride__scheduled_start_time_from/to` (1 h window) | `[]` after **9.7 s** |

Conclusion: Stride has no efficient "near this point" query. The home-screen "nearest stops overall" section was
**skipped** in v0.3, as the request allowed. The "nearest stop on this route" section uses the lat/lon already in the
direction's stop list (loaded anyway), so it costs no extra requests.
