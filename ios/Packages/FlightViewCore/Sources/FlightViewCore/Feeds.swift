import Foundation

/// Merges reports of the same aircraft from several feeds. Mirrors `AircraftMerge.kt` and
/// `backend/worker/src/merge.ts`: the freshest position wins, gaps are filled from the others.
public enum AircraftMerge {
    public static func merge(_ lists: [[Aircraft]]) -> [Aircraft] {
        var byId: [String: [Aircraft]] = [:]
        for list in lists { for a in list { byId[a.id, default: []].append(a) } }
        return byId.values.map { reports in
            let sorted = reports.sorted { $0.positionTime > $1.positionTime }
            var best = sorted[0]
            var sources = Set(best.sources ?? [])
            for r in sorted.dropFirst() {
                best.callsign = best.callsign ?? r.callsign
                best.registration = best.registration ?? r.registration
                best.typeCode = best.typeCode ?? r.typeCode
                best.typeName = best.typeName ?? r.typeName
                best.operator = best.operator ?? r.operator
                best.altGeomM = best.altGeomM ?? r.altGeomM
                best.altBaroM = best.altBaroM ?? r.altBaroM
                best.groundSpeedKt = best.groundSpeedKt ?? r.groundSpeedKt
                best.trackDeg = best.trackDeg ?? r.trackDeg
                best.verticalRateFpm = best.verticalRateFpm ?? r.verticalRateFpm
                best.category = best.category ?? r.category
                best.squawk = best.squawk ?? r.squawk
                sources.formUnion(r.sources ?? [])
            }
            best.sources = sources.sorted()
            return best
        }.sorted { $0.id < $1.id }
    }
}

/// Parsers for the public feeds the phone queries directly. Pure and unit-tested.
public enum FeedParsers {
    static let ftToM = 0.3048
    static let mpsToKt = 1 / 0.514444
    static let mpsToFpm = 196.850394

    private static func num(_ v: Any?) -> Double? {
        // No boolean fields are read through here, so plain NSNumber bridging is enough on every platform.
        (v as? NSNumber)?.doubleValue
    }

    private static func str(_ v: Any?) -> String? {
        guard let s = (v as? String)?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
        return s
    }

    private static func isHex(_ s: String) -> Bool {
        let body = s.hasPrefix("~") ? String(s.dropFirst()) : s
        return body.count == 6 && body.allSatisfy(\.isHexDigit)
    }

    /// readsb / tar1090 "v2" JSON (adsb.fi, ADSB.lol and others).
    public static func readsb(_ data: Data, source: String, fetchedAtS: Double) -> [Aircraft] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = (root["ac"] ?? root["aircraft"]) as? [[String: Any]] else { return [] }
        var now = num(root["now"]) ?? fetchedAtS
        if now > 1e12 { now /= 1000 }
        return list.compactMap { a in
            guard let hex = str(a["hex"]), isHex(hex), let lat = num(a["lat"]), let lon = num(a["lon"]),
                  abs(lat) <= 90, abs(lon) <= 180 else { return nil }
            let age = max(0, num(a["seen_pos"]) ?? num(a["seen"]) ?? 0)
            return Aircraft(
                id: hex.lowercased(), callsign: str(a["flight"]), registration: str(a["r"]), typeCode: str(a["t"]),
                typeName: str(a["desc"]), operator: str(a["ownOp"]), latDeg: lat, lonDeg: lon,
                altGeomM: num(a["alt_geom"]).map { $0 * ftToM }, altBaroM: num(a["alt_baro"]).map { $0 * ftToM },
                groundSpeedKt: num(a["gs"]), trackDeg: num(a["track"]),
                verticalRateFpm: num(a["geom_rate"]) ?? num(a["baro_rate"]), positionTime: now - age,
                onGround: (a["alt_baro"] as? String) == "ground", category: str(a["category"]),
                squawk: str(a["squawk"]), sources: [source]
            )
        }
    }

    /// OpenSky `/states/all` arrays (SI units).
    public static func openSky(_ data: Data, source: String) -> [Aircraft] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let states = root["states"] as? [[Any]] else { return [] }
        return states.compactMap { s in
            guard s.count >= 15, let icao = str(s[0]), let lon = num(s[5]), let lat = num(s[6]),
                  let t = num(s[3]) ?? num(s[4]) else { return nil }
            return Aircraft(
                id: icao.lowercased(), callsign: str(s[1]), registration: nil, typeCode: nil, typeName: nil,
                operator: nil, latDeg: lat, lonDeg: lon, altGeomM: num(s[13]), altBaroM: num(s[7]),
                groundSpeedKt: num(s[9]).map { $0 * mpsToKt }, trackDeg: num(s[10]),
                verticalRateFpm: num(s[11]).map { $0 * mpsToFpm }, positionTime: t,
                onGround: (s[8] as? Bool) == true, category: nil, squawk: str(s[14]), sources: [source]
            )
        }
    }
}
