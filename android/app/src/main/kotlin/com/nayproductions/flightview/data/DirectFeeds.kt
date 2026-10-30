package com.nayproductions.flightview.data

import com.nayproductions.flightview.core.Aircraft
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Feeds the phone queries itself, alongside the Worker.
 *
 * adsb.fi and the OpenSky Network block requests from cloud providers (including
 * Cloudflare Workers) but serve ordinary clients, so each phone asks them directly.
 * Only the same coarse tile centre the Worker sees is sent. Each feed is paced well
 * under its published limit and backs off when it fails.
 */
class DirectFeeds(
    private val http: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 4_000 }
        expectSuccess = false
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Feed(
        val id: String,
        val attribution: String,
        val minIntervalMs: Long,
        val url: (lat: Double, lon: Double, radiusKm: Int) -> String,
        val parse: (JsonObject, Double) -> List<Aircraft>,
    ) {
        var lastCallAt = 0L
        var retryAt = 0L
        var failures = 0
        var latest: List<Aircraft> = emptyList()
        var latestAt = 0L
    }

    private val feeds = listOf(
        Feed(
            id = "adsbfi",
            attribution = "adsb.fi open data",
            // Published limit is 1 request/second per client.
            minIntervalMs = 2_000,
            url = { lat, lon, r -> "https://opendata.adsb.fi/api/v2/lat/${f(lat)}/lon/${f(lon)}/dist/${nm(r)}" },
            parse = { json, now -> FeedParsers.readsb(json, "adsbfi", now) },
        ),
        Feed(
            id = "opensky",
            attribution = "The OpenSky Network",
            // Anonymous use is 400 credits a day per client; 20 s keeps a long session well inside it.
            minIntervalMs = 20_000,
            url = { lat, lon, r ->
                val dLat = r / 111.32
                val dLon = r / (111.32 * max(0.01, cos(Math.toRadians(lat))))
                "https://opensky-network.org/api/states/all?lamin=${f(lat - dLat)}&lomin=${f(lon - dLon)}" +
                    "&lamax=${f(lat + dLat)}&lomax=${f(lon + dLon)}"
            },
            parse = { json, _ -> FeedParsers.openSky(json, "opensky") },
        ),
    )

    /** Latest results from every direct feed (fresh ones only) plus which feeds contributed. */
    suspend fun poll(tileLat: Double, tileLon: Double, radiusKm: Int): Pair<List<List<Aircraft>>, List<String>> {
        val now = clock()
        for (feed in feeds) {
            if (now < feed.retryAt || now - feed.lastCallAt < feed.minIntervalMs) continue
            feed.lastCallAt = now
            try {
                val res = http.get(feed.url(tileLat, tileLon, radiusKm)) {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                }
                if (res.status != HttpStatusCode.OK) {
                    backOff(feed, now, if (res.status == HttpStatusCode.TooManyRequests) 300_000 else null)
                    continue
                }
                val json = Json.parseToJsonElement(res.bodyAsText()).jsonObject
                feed.latest = feed.parse(json, now / 1000.0)
                feed.latestAt = now
                feed.failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backOff(feed, now, null)
            }
        }
        val fresh = feeds.filter { it.latest.isNotEmpty() && now - it.latestAt < STALE_AFTER_MS }
        return fresh.map { it.latest } to fresh.map { it.attribution }
    }

    private fun backOff(feed: Feed, now: Long, fixedMs: Long?) {
        feed.failures += 1
        feed.retryAt = now + (fixedMs ?: min(120_000L, 5_000L shl min(feed.failures - 1, 5)))
    }

    private companion object {
        const val USER_AGENT = "FlightView/1.0 (Android; plane-spotting app)"
        const val STALE_AFTER_MS = 60_000L
        fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)
        fun nm(km: Int) = min(250, (km / 1.852).toInt() + 1)
    }
}

/** Parsers for the two public JSON shapes. Pure functions, unit-tested. */
object FeedParsers {
    private const val FT_TO_M = 0.3048
    private const val MPS_TO_KT = 1 / 0.514444
    private const val MPS_TO_FPM = 196.850394
    private val HEX = Regex("^~?[0-9a-f]{6}$", RegexOption.IGNORE_CASE)

    /** readsb / tar1090 "v2" JSON (adsb.fi, ADSB.lol and others). */
    fun readsb(json: JsonObject, source: String, fetchedAtS: Double): List<Aircraft> {
        val list = (json["ac"] ?: json["aircraft"]) as? JsonArray ?: return emptyList()
        var now = json.num("now") ?: fetchedAtS
        if (now > 1e12) now /= 1000
        return list.mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            val hex = a.str("hex") ?: return@mapNotNull null
            val lat = a.num("lat") ?: return@mapNotNull null
            val lon = a.num("lon") ?: return@mapNotNull null
            if (!HEX.matches(hex) || kotlin.math.abs(lat) > 90 || kotlin.math.abs(lon) > 180) return@mapNotNull null
            val altBaro = a["alt_baro"]
            Aircraft(
                id = hex.lowercase(),
                callsign = a.str("flight"),
                registration = a.str("r"),
                typeCode = a.str("t"),
                typeName = a.str("desc"),
                operator = a.str("ownOp"),
                latDeg = lat,
                lonDeg = lon,
                altGeomM = a.num("alt_geom")?.times(FT_TO_M),
                altBaroM = (altBaro as? JsonPrimitive)?.doubleOrNull?.times(FT_TO_M),
                groundSpeedKt = a.num("gs"),
                trackDeg = a.num("track"),
                verticalRateFpm = a.num("geom_rate") ?: a.num("baro_rate"),
                positionTime = now - max(0.0, a.num("seen_pos") ?: a.num("seen") ?: 0.0),
                onGround = (altBaro as? JsonPrimitive)?.content == "ground",
                category = a.str("category"),
                squawk = a.str("squawk"),
                sources = listOf(source),
            )
        }
    }

    /** OpenSky `/states/all` arrays (SI units). */
    fun openSky(json: JsonObject, source: String): List<Aircraft> {
        val states = json["states"] as? JsonArray ?: return emptyList()
        return states.mapNotNull { el ->
            val s = (el as? JsonArray)?.takeIf { it.size >= 15 } ?: return@mapNotNull null
            val icao = s.str(0) ?: return@mapNotNull null
            val lon = s.num(5) ?: return@mapNotNull null
            val lat = s.num(6) ?: return@mapNotNull null
            Aircraft(
                id = icao.lowercase(),
                callsign = s.str(1),
                registration = null, typeCode = null, typeName = null, operator = null,
                latDeg = lat,
                lonDeg = lon,
                altGeomM = s.num(13),
                altBaroM = s.num(7),
                groundSpeedKt = s.num(9)?.times(MPS_TO_KT),
                trackDeg = s.num(10),
                verticalRateFpm = s.num(11)?.times(MPS_TO_FPM),
                positionTime = s.num(3) ?: s.num(4) ?: return@mapNotNull null,
                onGround = (s[8] as? JsonPrimitive)?.booleanOrNull == true,
                category = null,
                squawk = s.str(14),
                sources = listOf(source),
            )
        }
    }

    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
    private fun JsonArray.num(i: Int) = (getOrNull(i) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonArray.str(i: Int) = (getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
}
