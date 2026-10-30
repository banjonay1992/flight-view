import Foundation

/// One aircraft as returned by the Flight View API (`/v1/aircraft`).
public struct Aircraft: Codable, Identifiable, Equatable, Sendable {
    public var id: String
    public var callsign: String?
    public var registration: String?
    public var typeCode: String?
    public var typeName: String?
    public var `operator`: String?
    public var latDeg: Double
    public var lonDeg: Double
    public var altGeomM: Double?
    public var altBaroM: Double?
    public var groundSpeedKt: Double?
    public var trackDeg: Double?
    public var verticalRateFpm: Double?
    /// Unix seconds at which the position was measured.
    public var positionTime: Double
    public var onGround: Bool
    public var category: String?
    public var squawk: String?
    public var sources: [String]?

    /// Prefer geometric (GNSS, ellipsoidal) height; barometric altitude can be hundreds of metres off.
    public var bestHeightM: Double? { altGeomM ?? altBaroM }

    public var displayName: String {
        if let c = callsign?.trimmingCharacters(in: .whitespaces), !c.isEmpty { return c }
        return registration ?? id.uppercased()
    }

    public init(
        id: String, callsign: String?, registration: String?, typeCode: String?, typeName: String?,
        operator: String?, latDeg: Double, lonDeg: Double, altGeomM: Double?, altBaroM: Double?,
        groundSpeedKt: Double?, trackDeg: Double?, verticalRateFpm: Double?, positionTime: Double,
        onGround: Bool, category: String?, squawk: String?, sources: [String]? = nil
    ) {
        self.id = id
        self.callsign = callsign
        self.registration = registration
        self.typeCode = typeCode
        self.typeName = typeName
        self.operator = `operator`
        self.latDeg = latDeg
        self.lonDeg = lonDeg
        self.altGeomM = altGeomM
        self.altBaroM = altBaroM
        self.groundSpeedKt = groundSpeedKt
        self.trackDeg = trackDeg
        self.verticalRateFpm = verticalRateFpm
        self.positionTime = positionTime
        self.onGround = onGround
        self.category = category
        self.squawk = squawk
        self.sources = sources
    }
}

public struct AircraftResponse: Codable, Sendable {
    public struct Area: Codable, Sendable { public var latDeg: Double; public var lonDeg: Double; public var radiusKm: Double }
    public var serverTime: Double
    public var area: Area
    public var aircraft: [Aircraft]
    public var attribution: [String]
}

public struct SkyMarker: Identifiable, Sendable {
    public var aircraft: Aircraft
    public var position: GeoPoint
    public var look: LookAngles
    public var screen: ScreenPoint
    /// Direction to turn when off screen (0 = right, pi/2 = up).
    public var edgeAngleRad: Double
    public var positionAgeS: Double
    public var id: String { aircraft.id }
    public var isStale: Bool { positionAgeS > SkyScene.staleAfterS }
}

public struct SceneConfig: Sendable {
    public var maxRangeM: Double = 80_000
    /// Slightly negative so aircraft on the visual horizon survive GPS height error.
    public var minElevationDeg: Double = -1
    public var includeOnGround = false
    public init() {}
}

public enum SkyScene {
    public static let staleAfterS = 30.0
    public static let dropAfterS = 120.0

    /// Build the visible scene for one frame. `nowS` is on the server clock; `rDw` is true-north ENU.
    public static func build(
        aircraft: [Aircraft], observer: GeoPoint, nowS: Double, rDw: Mat3,
        k: Intrinsics, viewW: Double, viewH: Double, config: SceneConfig = SceneConfig()
    ) -> [SkyMarker] {
        var out: [SkyMarker] = []
        out.reserveCapacity(aircraft.count)
        for a in aircraft {
            if a.onGround && !config.includeOnGround { continue }
            guard let h = a.bestHeightM else { continue }
            let age = nowS - a.positionTime
            if age > dropAfterS { continue }
            let pos = Geodesy.deadReckon(
                GeoPoint(latDeg: a.latDeg, lonDeg: a.lonDeg, heightM: h),
                groundSpeedKt: a.groundSpeedKt, trackDeg: a.trackDeg, verticalRateFpm: a.verticalRateFpm, dtS: age
            )
            let look = Geodesy.lookAngles(from: observer, to: pos)
            if look.slantRangeM > config.maxRangeM || look.elevationDeg < config.minElevationDeg { continue }
            let dir = Geodesy.directionEnu(azimuthDeg: look.azimuthDeg, elevationDeg: look.elevationDeg)
            let screen = Projection.project(rDw, worldDir: dir, k: k, viewW: viewW, viewH: viewH)
            out.append(SkyMarker(aircraft: a, position: pos, look: look, screen: screen,
                                 edgeAngleRad: Projection.edgeAngleRad(rDw, worldDir: dir), positionAgeS: age))
        }
        out.sort { $0.look.slantRangeM < $1.look.slantRangeM }
        return out
    }

    /// Nearest on-screen marker to a tap within `radius` points.
    public static func hitTest(_ markers: [SkyMarker], x: Double, y: Double, radius: Double = 44) -> SkyMarker? {
        markers
            .filter { $0.screen.onScreen }
            .map { ($0, hypot($0.screen.x - x, $0.screen.y - y)) }
            .filter { $0.1 <= radius }
            .min { $0.1 < $1.1 }?.0
    }
}

/// Exponential smoothing of marker positions; snaps on large jumps so labels never trail.
public final class ScreenSmoother {
    private struct State { var x: Double, y: Double, t: Double }
    private var states: [String: State] = [:]
    private let timeConstantS: Double
    private let snapDistance: Double

    public init(timeConstantS: Double = 0.12, snapDistance: Double = 160) {
        self.timeConstantS = timeConstantS
        self.snapDistance = snapDistance
    }

    public func smooth(id: String, x: Double, y: Double, t: Double) -> (x: Double, y: Double) {
        let next: State
        if let p = states[id], t - p.t <= 0.5, hypot(x - p.x, y - p.y) <= snapDistance {
            let alpha = 1 - exp(-max(0, t - p.t) / timeConstantS)
            next = State(x: p.x + (x - p.x) * alpha, y: p.y + (y - p.y) * alpha, t: t)
        } else {
            next = State(x: x, y: y, t: t)
        }
        states[id] = next
        return (next.x, next.y)
    }

    public func retain(_ ids: Set<String>) {
        states = states.filter { ids.contains($0.key) }
    }
}

public struct PlacedLabel: Equatable, Sendable {
    public var id: String, left: Double, top: Double, width: Double, height: Double
}

/// Greedy declutter, nearest first: above, stacked above, below, then further out; else dot only.
public enum LabelLayout {
    static let spacing = 4.0

    public static func place(
        anchors: [(id: String, x: Double, y: Double)], sizes: [String: (w: Double, h: Double)],
        viewW: Double, viewH: Double, markerGap: Double = 14, maxLabels: Int = 14
    ) -> [PlacedLabel] {
        var placed: [PlacedLabel] = []
        for a in anchors {
            if placed.count >= maxLabels { break }
            guard let size = sizes[a.id] else { continue }
            let row = size.h + spacing
            let above = a.y - markerGap - size.h
            let below = a.y + markerGap
            let tops = [above, above - row, below, above - 2 * row, below + row]
            let left = min(max(a.x - size.w / 2, 4), max(4, viewW - size.w - 4))
            for top in tops where top >= 0 && top + size.h <= viewH {
                let c = PlacedLabel(id: a.id, left: left, top: top, width: size.w, height: size.h)
                if !placed.contains(where: { overlaps($0, c) }) {
                    placed.append(c)
                    break
                }
            }
        }
        return placed
    }

    static func overlaps(_ a: PlacedLabel, _ b: PlacedLabel) -> Bool {
        abs((a.left + a.width / 2) - (b.left + b.width / 2)) * 2 < a.width + b.width &&
            abs((a.top + a.height / 2) - (b.top + b.height / 2)) * 2 < a.height + b.height
    }
}
