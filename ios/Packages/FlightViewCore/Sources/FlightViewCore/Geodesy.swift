import Foundation

/// Latitude/longitude in degrees, height in metres above the WGS84 ellipsoid.
public struct GeoPoint: Equatable, Sendable {
    public var latDeg: Double
    public var lonDeg: Double
    public var heightM: Double

    public init(latDeg: Double, lonDeg: Double, heightM: Double) {
        self.latDeg = latDeg
        self.lonDeg = lonDeg
        self.heightM = heightM
    }
}

public struct LookAngles: Equatable, Sendable {
    public var azimuthDeg: Double
    public var elevationDeg: Double
    public var slantRangeM: Double
}

/// WGS84 geodesy. Mirrors `shared/reference/flightmath.py`; change that first and regenerate fixtures.
public enum Geodesy {
    public static let wgs84A = 6_378_137.0
    public static let wgs84F = 1.0 / 298.257223563
    public static let wgs84E2 = wgs84F * (2.0 - wgs84F)
    public static let meanEarthRadiusM = 6_371_008.8
    public static let knotToMps = 0.514444
    public static let footToM = 0.3048
    public static let maxExtrapolationS = 60.0

    @inline(__always) static func rad(_ d: Double) -> Double { d * (.pi / 180) }
    @inline(__always) static func deg(_ r: Double) -> Double { r * (180 / .pi) }

    public static func geodeticToEcef(_ p: GeoPoint) -> Vec3 {
        let lat = rad(p.latDeg), lon = rad(p.lonDeg)
        let s = sin(lat)
        let n = wgs84A / (1 - wgs84E2 * s * s).squareRoot()
        return Vec3(
            (n + p.heightM) * cos(lat) * cos(lon),
            (n + p.heightM) * cos(lat) * sin(lon),
            (n * (1 - wgs84E2) + p.heightM) * s
        )
    }

    public static func ecefDeltaToEnu(_ d: Vec3, lat0Deg: Double, lon0Deg: Double) -> Vec3 {
        let lat = rad(lat0Deg), lon = rad(lon0Deg)
        let sl = sin(lat), cl = cos(lat), so = sin(lon), co = cos(lon)
        return Vec3(
            -so * d.x + co * d.y,
            -sl * co * d.x - sl * so * d.y + cl * d.z,
            cl * co * d.x + cl * so * d.y + sl * d.z
        )
    }

    public static func enu(of target: GeoPoint, from observer: GeoPoint) -> Vec3 {
        ecefDeltaToEnu(geodeticToEcef(target) - geodeticToEcef(observer), lat0Deg: observer.latDeg, lon0Deg: observer.lonDeg)
    }

    public static func lookAngles(from observer: GeoPoint, to target: GeoPoint) -> LookAngles {
        let e = enu(of: target, from: observer)
        var az = deg(atan2(e.x, e.y)).truncatingRemainder(dividingBy: 360)
        if az < 0 { az += 360 }
        let el = deg(atan2(e.z, hypot(e.x, e.y)))
        return LookAngles(azimuthDeg: az, elevationDeg: el, slantRangeM: e.length)
    }

    public static func directionEnu(azimuthDeg: Double, elevationDeg: Double) -> Vec3 {
        let az = rad(azimuthDeg), el = rad(elevationDeg)
        return Vec3(cos(el) * sin(az), cos(el) * cos(az), sin(el))
    }

    /// Advance a position along a great circle for `dtS` seconds (clamped to 0...60 s).
    public static func deadReckon(
        _ start: GeoPoint,
        groundSpeedKt: Double?,
        trackDeg: Double?,
        verticalRateFpm: Double?,
        dtS: Double
    ) -> GeoPoint {
        let dt = max(0, min(dtS, maxExtrapolationS))
        var lat = start.latDeg, lon = start.lonDeg
        if let gs = groundSpeedKt, let trk = trackDeg, dt != 0 {
            let delta = gs * knotToMps * dt / meanEarthRadiusM
            let theta = rad(trk), phi1 = rad(start.latDeg), lam1 = rad(start.lonDeg)
            let phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
            let lam2 = lam1 + atan2(sin(theta) * sin(delta) * cos(phi1), cos(delta) - sin(phi1) * sin(phi2))
            lat = deg(phi2)
            // Floored modulo, as in the Python reference and Kotlin's Double.mod.
            var r = (deg(lam2) + 540).truncatingRemainder(dividingBy: 360)
            if r < 0 { r += 360 }
            lon = r - 180
        }
        let alt = start.heightM + ((verticalRateFpm ?? 0) * footToM / 60) * dt
        return GeoPoint(latDeg: lat, lonDeg: lon, heightM: max(alt, -500))
    }
}
