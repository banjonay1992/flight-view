import FlightViewCore
import Foundation
import Observation

/// Owns polling and per-frame scene building for the camera view.
@Observable
@MainActor
final class SkyViewModel {
    private(set) var aircraft: [Aircraft] = []
    private(set) var attribution: [String] = []
    private(set) var statusMessage: String?
    private(set) var lastUpdate: Date?
    var selectedID: String?

    /// User's manual compass correction in degrees (drag in calibration mode). Persisted.
    var headingNudgeDeg: Double {
        didSet { UserDefaults.standard.set(headingNudgeDeg, forKey: Self.nudgeKey) }
    }

    private static let nudgeKey = "headingNudgeDeg"
    private static let pollInterval: Duration = .seconds(2)

    @ObservationIgnored private let env: AppEnvironment
    @ObservationIgnored private var pollTask: Task<Void, Never>?
    @ObservationIgnored private let smoother = ScreenSmoother()
    private var clockOffsetS: Double = 0

    init(env: AppEnvironment) {
        self.env = env
        headingNudgeDeg = UserDefaults.standard.double(forKey: Self.nudgeKey)
    }

    func start() {
        env.location.start()
        env.motion.start()
        Task { await env.camera.start() }
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.poll()
                try? await Task.sleep(for: Self.pollInterval)
            }
        }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
        env.motion.stop()
        env.camera.stop()
        env.location.stop()
    }

    private func poll() async {
        guard let obs = env.location.observer else {
            statusMessage = String(localized: "Finding your location…")
            return
        }
        do {
            let res = try await env.api.fetch(latDeg: obs.latDeg, lonDeg: obs.lonDeg)
            aircraft = res.aircraft
            attribution = res.attribution
            clockOffsetS = await env.api.clockOffsetS
            lastUpdate = .now
            statusMessage = nil
        } catch is CancellationError {
            return
        } catch {
            // Keep showing the last good data; dead reckoning carries it for a while.
            statusMessage = error.localizedDescription
        }
    }

    /// Server-clock "now", used for dead reckoning.
    var nowS: Double { Date().timeIntervalSince1970 + clockOffsetS }

    struct Frame {
        var markers: [SkyMarker]
        var labels: [PlacedLabel]
        var smoothed: [String: CGPoint]
        var cameraHeadingDeg: Double?
    }

    static let labelSize = (w: 132.0, h: 40.0)

    /// Build one frame for the given view size. Cheap enough for 60 fps with a few hundred aircraft.
    func frame(size: CGSize) -> Frame? {
        guard let obs = env.location.observer, let raw = env.motion.attitude else { return nil }
        let rDw = Projection.rotateAboutUp(raw, degreesClockwise: headingNudgeDeg)
        let w = Double(size.width), h = Double(size.height)
        let k = Intrinsics.aspectFill(longSideFovDeg: env.camera.longSideFovDeg,
                                      imageAspect: env.camera.imageAspect, viewW: w, viewH: h)
        let markers = SkyScene.build(aircraft: aircraft, observer: obs, nowS: nowS, rDw: rDw, k: k, viewW: w, viewH: h)

        let t = Date().timeIntervalSince1970
        var smoothed: [String: CGPoint] = [:]
        for m in markers where m.screen.onScreen {
            let p = smoother.smooth(id: m.id, x: m.screen.x, y: m.screen.y, t: t)
            smoothed[m.id] = CGPoint(x: p.x, y: p.y)
        }
        smoother.retain(Set(smoothed.keys))

        let anchors = markers.compactMap { m in smoothed[m.id].map { (id: m.id, x: Double($0.x), y: Double($0.y)) } }
        let sizes = Dictionary(uniqueKeysWithValues: anchors.map { ($0.id, Self.labelSize) })
        let labels = LabelLayout.place(anchors: anchors, sizes: sizes, viewW: w, viewH: h)

        let axis = rDw * Vec3(0, 0, -1)
        var heading = atan2(axis.x, axis.y) * 180 / .pi
        if heading < 0 { heading += 360 }
        return Frame(markers: markers, labels: labels, smoothed: smoothed,
                     cameraHeadingDeg: hypot(axis.x, axis.y) > 0.2 ? heading : nil)
    }

    func select(at point: CGPoint, in frame: Frame) {
        let candidates = frame.markers.compactMap { m -> SkyMarker? in
            guard let p = frame.smoothed[m.id] else { return nil }
            var copy = m
            copy.screen.x = Double(p.x)
            copy.screen.y = Double(p.y)
            return copy
        }
        selectedID = SkyScene.hitTest(candidates, x: Double(point.x), y: Double(point.y))?.id
    }

    /// Live position, distance and direction for one aircraft, independent of where the camera points.
    func snapshot(id: String) -> SkyMarker? {
        guard let obs = env.location.observer, let a = aircraft.first(where: { $0.id == id }) else { return nil }
        var cfg = SceneConfig()
        cfg.maxRangeM = .infinity
        cfg.minElevationDeg = -90
        let k = Intrinsics(fx: 1, fy: 1, cx: 0, cy: 0)
        return SkyScene.build(aircraft: [a], observer: obs, nowS: nowS, rDw: .identity, k: k,
                              viewW: 1, viewH: 1, config: cfg).first
    }

    /// Everything in range, nearest first, regardless of camera direction. Used by the list view.
    func nearby() -> [SkyMarker] {
        guard let obs = env.location.observer else { return [] }
        let k = Intrinsics(fx: 1, fy: 1, cx: 0, cy: 0)
        return SkyScene.build(aircraft: aircraft, observer: obs, nowS: nowS, rDw: .identity, k: k, viewW: 1, viewH: 1)
    }

    /// Current camera heading, for "2 o'clock"-style directions in the accessible list.
    var cameraHeadingDeg: Double? {
        guard let raw = env.motion.attitude else { return nil }
        let axis = Projection.rotateAboutUp(raw, degreesClockwise: headingNudgeDeg) * Vec3(0, 0, -1)
        guard hypot(axis.x, axis.y) > 0.2 else { return nil }
        let h = atan2(axis.x, axis.y) * 180 / .pi
        return h < 0 ? h + 360 : h
    }

    func save(_ marker: SkyMarker) async -> Bool {
        guard let obs = env.location.observer else { return false }
        do {
            try await env.logbook.save(marker, observer: obs)
            return true
        } catch {
            statusMessage = String(localized: "Couldn't save to your logbook.")
            return false
        }
    }
}
