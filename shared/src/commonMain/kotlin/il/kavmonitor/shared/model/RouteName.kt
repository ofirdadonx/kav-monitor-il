package il.kavmonitor.shared.model

/** One end of a route: stop name and city (city may be unknown). */
data class RouteEnd(val stop: String, val city: String?) {
    val label: String get() = if (city.isNullOrBlank()) stop else "$stop, $city"
}

/**
 * GTFS `route_long_name` as published by the MOT: `ORIGIN_STOP-ORIGIN_CITY<->DEST_STOP-DEST_CITY-<dir><alt>`, e.g.
 * `ת. רכבת תל אביב- סבידור/רציפים A-תל אביב יפו<->ת. רכבת הראשונים-ראשון לציון-1#`.
 *
 * Stop names may themselves contain '-' (`ת. רכבת תל אביב- סבידור`, `יפיע/אל-אלמאניה`), so the city is the text after
 * the LAST '-', except for city names that contain a hyphen (Arabic article `אל-`/`א-`, a few known names).
 * The trailing `-<route_direction><route_alternative>` code (`-1#`, `-20`, `-3ק`) is removed.
 */
data class RouteName(val origin: RouteEnd, val destination: RouteEnd) {
    companion object {
        private val SUFFIX = Regex("""-\s*[0-9][#0-9\p{L}]?\s*$""")
        private val ARTICLE_END = Regex("""(^|[\s/-])(אל|א)$""")
        private val KNOWN_HYPHEN_CITIES = listOf("בועיינה-נוג'ידאת", "תל אביב-יפו")

        private val ESCAPED_GERSHAYIM = Regex("''+")

        /** MOT data writes gershayim as doubled (sometimes quadrupled) apostrophes: `צומת חב''ד` → `צומת חב"ד`. */
        fun cleanName(s: String): String = s.replace(ESCAPED_GERSHAYIM, "\"")

        fun parse(longName: String, direction: String = "", alternative: String = ""): RouteName {
            val parts = cleanName(longName).trim().split("<->", limit = 2)
            val origin = splitEnd(parts[0])
            val destRaw = stripSuffix(parts.getOrElse(1) { "" }, direction, alternative)
            val destination = if (destRaw.isBlank()) RouteEnd("", null) else splitEnd(destRaw)
            return RouteName(origin, destination)
        }

        internal fun stripSuffix(s: String, direction: String, alternative: String): String {
            val t = s.trim()
            val exact = "-${direction.trim()}${alternative.trim()}"
            if (direction.isNotBlank() && t.endsWith(exact)) return t.dropLast(exact.length).trim()
            return t.replace(SUFFIX, "").trim()
        }

        internal fun splitEnd(raw: String): RouteEnd {
            val s = raw.trim()
            KNOWN_HYPHEN_CITIES.firstOrNull { s.endsWith("-$it") }?.let { city ->
                return RouteEnd(s.dropLast(city.length + 1).trim(), city)
            }
            val idx = s.lastIndexOf('-')
            if (idx <= 0 || idx == s.length - 1) return RouteEnd(s, null)
            var stop = s.substring(0, idx).trim()
            var city = s.substring(idx + 1).trim()
            if (ARTICLE_END.containsMatchIn(stop)) {
                // "…-אום אל-פחם": the hyphen belongs to the city name
                val prev = stop.lastIndexOf('-')
                if (prev > 0) {
                    city = stop.substring(prev + 1).trim() + "-" + city
                    stop = stop.substring(0, prev).trim()
                }
            }
            return if (stop.isEmpty()) RouteEnd(s, null) else RouteEnd(stop, city.ifBlank { null })
        }
    }
}
