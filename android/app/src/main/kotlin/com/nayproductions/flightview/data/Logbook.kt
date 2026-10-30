package com.nayproductions.flightview.data

import com.nayproductions.flightview.core.GeoPoint
import com.nayproductions.flightview.core.SkyMarker
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Spot(
    val id: String,
    val icao24: String,
    val callsign: String? = null,
    val registration: String? = null,
    @SerialName("type_code") val typeCode: String? = null,
    @SerialName("type_name") val typeName: String? = null,
    val operator: String? = null,
    @SerialName("spotted_at") val spottedAt: String,
)

@Serializable
private data class SpotInsert(
    val icao24: String,
    val callsign: String?,
    val registration: String?,
    @SerialName("type_code") val typeCode: String?,
    @SerialName("type_name") val typeName: String?,
    val operator: String?,
    @SerialName("altitude_m") val altitudeM: Double,
    @SerialName("slant_range_km") val slantRangeKm: Double,
    @SerialName("elevation_deg") val elevationDeg: Double,
    @SerialName("tile_lat") val tileLat: Double,
    @SerialName("tile_lon") val tileLon: Double,
)

/** The user's spotting log. RLS restricts every query to the signed-in user. */
class Logbook(private val session: Session) {
    suspend fun save(marker: SkyMarker, observer: GeoPoint) {
        session.accessToken()
        val a = marker.aircraft
        val (tLat, tLon) = AircraftApi.tileCentre(observer.latDeg, observer.lonDeg)
        session.client.from("spots").insert(
            SpotInsert(
                icao24 = a.id, callsign = a.callsign, registration = a.registration, typeCode = a.typeCode,
                typeName = a.typeName, operator = a.operator, altitudeM = marker.position.heightM,
                slantRangeKm = marker.look.slantRangeM / 1000, elevationDeg = marker.look.elevationDeg,
                tileLat = tLat, tileLon = tLon,
            ),
        )
    }

    suspend fun recent(limit: Long = 100): List<Spot> {
        session.accessToken()
        return session.client.from("spots").select {
            order("spotted_at", Order.DESCENDING)
            limit(limit)
        }.decodeList()
    }

    suspend fun delete(spot: Spot) {
        session.client.from("spots").delete { filter { eq("id", spot.id) } }
    }
}
