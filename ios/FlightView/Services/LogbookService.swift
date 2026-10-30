import FlightViewCore
import Foundation
import Supabase

struct Spot: Codable, Identifiable, Hashable {
    let id: UUID
    let icao24: String
    let callsign: String?
    let registration: String?
    let typeCode: String?
    let typeName: String?
    let `operator`: String?
    let altitudeM: Double?
    let slantRangeKm: Double?
    let spottedAt: Date

    enum CodingKeys: String, CodingKey {
        case id, icao24, callsign, registration, `operator`
        case typeCode = "type_code", typeName = "type_name", altitudeM = "altitude_m"
        case slantRangeKm = "slant_range_km", spottedAt = "spotted_at"
    }
}

private struct SpotInsert: Encodable {
    let icao24: String
    let callsign: String?
    let registration: String?
    let type_code: String?
    let type_name: String?
    let `operator`: String?
    let altitude_m: Double?
    let slant_range_km: Double?
    let elevation_deg: Double?
    let tile_lat: Double
    let tile_lon: Double
}

/// The user's spotting log in Supabase. RLS limits every query to the signed-in user.
@MainActor
final class LogbookService {
    private let session: SessionStore

    init(session: SessionStore) {
        self.session = session
    }

    func save(_ marker: SkyMarker, observer: GeoPoint) async throws {
        _ = try await session.accessToken() // make sure we're signed in
        let a = marker.aircraft
        let tile = AircraftAPI.tileCentre(latDeg: observer.latDeg, lonDeg: observer.lonDeg)
        let row = SpotInsert(
            icao24: a.id, callsign: a.callsign, registration: a.registration, type_code: a.typeCode,
            type_name: a.typeName, operator: a.operator, altitude_m: marker.position.heightM,
            slant_range_km: marker.look.slantRangeM / 1000, elevation_deg: marker.look.elevationDeg,
            tile_lat: tile.lat, tile_lon: tile.lon
        )
        try await session.client.from("spots").insert(row).execute()
    }

    func recent(limit: Int = 100) async throws -> [Spot] {
        _ = try await session.accessToken()
        return try await session.client.from("spots")
            .select("id, icao24, callsign, registration, type_code, type_name, operator, altitude_m, slant_range_km, spotted_at")
            .order("spotted_at", ascending: false)
            .limit(limit)
            .execute()
            .value
    }

    func delete(_ spot: Spot) async throws {
        try await session.client.from("spots").delete().eq("id", value: spot.id.uuidString).execute()
    }
}
