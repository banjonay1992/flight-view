@testable import FlightViewCore
import XCTest

/// Mirrors android/core/src/test/.../SceneChecks.kt so both platforms behave identically.
final class SceneTests: XCTestCase {
    let observer = GeoPoint(latDeg: 50.2213, lonDeg: -5.2468, heightM: 230)
    let facingNorth = Mat3(rows: [1, 0, 0], [0, 0, -1], [0, 1, 0])
    let w = 390.0, h = 844.0, now = 1_800_000_000.0

    func plane(_ id: String, _ dLat: Double, _ dLon: Double, _ alt: Double, ground: Bool = false, age: Double = 2) -> Aircraft {
        Aircraft(id: id, callsign: id.uppercased(), registration: nil, typeCode: "A320", typeName: nil, operator: nil,
                 latDeg: observer.latDeg + dLat, lonDeg: observer.lonDeg + dLon, altGeomM: alt, altBaroM: nil,
                 groundSpeedKt: 420, trackDeg: 90, verticalRateFpm: 0, positionTime: now - age,
                 onGround: ground, category: "A3", squawk: nil)
    }

    func sampleScene() -> [SkyMarker] {
        let k = Intrinsics.aspectFill(longSideFovDeg: 65, imageAspect: 4.0 / 3.0, viewW: w, viewH: h)
        let planes = [
            plane("near", 0.20, 0, 9000), plane("left", 0.25, -0.03, 10000), plane("right", 0.30, 0.04, 11000),
            plane("far", 1.5, 0, 11000), plane("ground", 0.02, 0, 120, ground: true), plane("old", 0.1, 0.01, 9000, age: 500),
        ]
        return SkyScene.build(aircraft: planes, observer: observer, nowS: now, rDw: facingNorth, k: k, viewW: w, viewH: h)
    }

    func testFilteringAndOrdering() {
        let scene = sampleScene()
        XCTAssertEqual(scene.map(\.id), ["near", "left", "right"])
        let near = scene[0]
        XCTAssertTrue(near.screen.onScreen)
        XCTAssertLessThan(near.screen.y, h / 2, "above the horizon should be above centre")
        XCTAssertLessThan(scene[1].screen.x, w / 2, "west of north should be left of centre")
        XCTAssertGreaterThan(scene[2].screen.x, w / 2)
    }

    func testHitTest() {
        let scene = sampleScene()
        let t = scene[0]
        XCTAssertEqual(SkyScene.hitTest(scene, x: t.screen.x + 5, y: t.screen.y - 5)?.id, t.id)
        XCTAssertNil(SkyScene.hitTest(scene, x: -500, y: -500))
    }

    func testLabelsNeverOverlap() {
        let anchors = (0..<30).map { (id: "p\($0)", x: 180.0 + Double($0 % 3) * 6, y: 300.0 + Double($0 % 5) * 3) }
        let sizes = Dictionary(uniqueKeysWithValues: anchors.map { ($0.id, (w: 110.0, h: 34.0)) })
        let placed = LabelLayout.place(anchors: anchors, sizes: sizes, viewW: w, viewH: h)
        XCTAssertFalse(placed.isEmpty)
        for i in placed.indices {
            for j in placed.indices where j > i {
                XCTAssertFalse(LabelLayout.overlaps(placed[i], placed[j]))
            }
        }
    }

    func testSmoother() {
        let s = ScreenSmoother()
        _ = s.smooth(id: "a", x: 100, y: 100, t: 0)
        let eased = s.smooth(id: "a", x: 110, y: 100, t: 1.0 / 60)
        XCTAssertTrue(eased.x > 100 && eased.x < 110)
        XCTAssertEqual(s.smooth(id: "a", x: 600, y: 100, t: 2.0 / 60).x, 600)
    }

    func testAttitudeResolverPicksTheOrientationConsistentWithGravity() {
        // Upright, facing north. Device->reference (N, W, U) matrix:
        let deviceToRef = Mat3(rows: [0, 0, -1], [-1, 0, 0], [0, 1, 0])
        let gravity = Vec3(0, -1, 0) // device +Y is up, so gravity points down -Y
        var a = AttitudeResolver()
        XCTAssertEqual(a.resolve(rows: deviceToRef, gravity: gravity), facingNorth)
        var b = AttitudeResolver()
        XCTAssertEqual(b.resolve(rows: deviceToRef.transposed, gravity: gravity), facingNorth)
        var flat = AttitudeResolver()
        XCTAssertNil(flat.resolve(rows: .identity, gravity: Vec3(0, 0, -1)), "flat on a table is ambiguous; wait")
    }
}
