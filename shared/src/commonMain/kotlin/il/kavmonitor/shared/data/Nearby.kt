package il.kavmonitor.shared.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Stops around a point (curlbus `/nearby`). */
interface NearbySource {
    suspend fun nearby(lat: Double, lon: Double, radius: Int): List<NearbyStop>
}

/** One stop from curlbus `/nearby` (straight-line [distanceMeters] computed by curlbus). */
data class NearbyStop(val code: String, val nameHe: String, val distanceMeters: Double, val lat: Double?, val lon: Double?)

@Serializable
private data class NearbyDto(
    val code: JsonPrimitive? = null,
    val name: Map<String, String?>? = null,
    val distance: Double? = null,
    val location: Map<String, JsonPrimitive?>? = null,
)

object NearbyStops {
    /** curlbus accepts 5, 10 and 50..1000 m in steps of 50. */
    val ALLOWED_RADII: Set<Int> = setOf(5, 10) + (50..1000 step 50).toSet()
    /** Radius steps tried in order until something is found (home screen). */
    val EXPANDING_RADII = listOf(300, 500, 1000)

    /** ~100 m cell (3 decimals ≈ 111 m north-south, 94 m east-west here) for caching `/nearby` answers. */
    fun cacheKey(lat: Double, lon: Double): String = "${kotlin.math.round(lat * 1000).toLong()}:${kotlin.math.round(lon * 1000).toLong()}"

    fun parse(json: String): List<NearbyStop> =
        ApiJson.decodeFromString(ListSerializer(NearbyDto.serializer()), json).mapNotNull { d ->
            val code = d.code?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NearbyStop(
                code = code,
                nameHe = d.name?.get("HE") ?: d.name?.get("EN") ?: code,
                distanceMeters = d.distance ?: Double.MAX_VALUE,
                lat = d.location?.get("lat")?.doubleOrNull,
                lon = d.location?.get("lon")?.doubleOrNull,
            )
        }.sortedBy { it.distanceMeters } // curlbus does not sort
}
