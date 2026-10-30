package com.nayproductions.flightview.data

import com.nayproductions.flightview.core.Aircraft
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.floor

@Serializable
data class ApiAircraft(
    val id: String,
    val callsign: String? = null,
    val registration: String? = null,
    val typeCode: String? = null,
    val typeName: String? = null,
    val operator: String? = null,
    val latDeg: Double,
    val lonDeg: Double,
    val altGeomM: Double? = null,
    val altBaroM: Double? = null,
    val groundSpeedKt: Double? = null,
    val trackDeg: Double? = null,
    val verticalRateFpm: Double? = null,
    val positionTime: Double,
    val onGround: Boolean = false,
    val category: String? = null,
    val squawk: String? = null,
    val sources: List<String> = emptyList(),
) {
    fun toCore() = Aircraft(
        id, callsign, registration, typeCode, typeName, operator, latDeg, lonDeg, altGeomM, altBaroM,
        groundSpeedKt, trackDeg, verticalRateFpm, positionTime, onGround, category, squawk, sources,
    )
}

@Serializable
data class AircraftResponse(
    val serverTime: Double,
    val aircraft: List<ApiAircraft>,
    val attribution: List<String> = emptyList(),
)

sealed class ApiException(message: String) : Exception(message) {
    class Busy : ApiException("busy")
    class Server(val code: Int) : ApiException("HTTP $code")
}

/**
 * Client for the Flight View Worker. The precise location never leaves the device: it is
 * snapped to the same 0.1 degree tile the server uses before the request is built.
 */
class AircraftApi(private val baseUrl: String, private val session: Session) {
    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 8_000 }
        expectSuccess = false
    }

    /** serverTime - deviceTime, smoothed. Keeps dead reckoning correct on phones with a wrong clock. */
    @Volatile var clockOffsetS: Double = 0.0
        private set

    suspend fun fetch(latDeg: Double, lonDeg: Double, radiusKm: Int = 80): AircraftResponse {
        val (lat, lon) = tileCentre(latDeg, lonDeg)
        val token = session.accessToken()
        val sentAt = System.currentTimeMillis() / 1000.0
        val res = http.get("${baseUrl.trimEnd('/')}/v1/aircraft") {
            bearerAuth(token)
            parameter("lat", String.format(Locale.ROOT, "%.2f", lat))
            parameter("lon", String.format(Locale.ROOT, "%.2f", lon))
            parameter("radius_km", radiusKm)
        }
        val receivedAt = System.currentTimeMillis() / 1000.0
        when (res.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable -> throw ApiException.Busy()
            else -> throw ApiException.Server(res.status.value)
        }
        val body: AircraftResponse = res.body()
        val measured = body.serverTime - (sentAt + receivedAt) / 2
        clockOffsetS = if (clockOffsetS == 0.0) measured else clockOffsetS * 0.8 + measured * 0.2
        return body
    }

    companion object {
        const val TILE_DEG = 0.1

        fun tileCentre(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
            val lat = (floor(latDeg / TILE_DEG) + 0.5) * TILE_DEG
            val lon = (floor(lonDeg / TILE_DEG) + 0.5) * TILE_DEG
            return Math.round(lat * 100) / 100.0 to Math.round(lon * 100) / 100.0
        }
    }
}
