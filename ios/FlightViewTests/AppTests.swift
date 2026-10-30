@testable import FlightView
import XCTest

final class AppTests: XCTestCase {
    func testTileCentreMatchesServerGrid() async {
        let t = AircraftAPI.tileCentre(latDeg: 50.23456, lonDeg: -5.23456)
        XCTAssertEqual(t.lat, 50.25, accuracy: 1e-9)
        XCTAssertEqual(t.lon, -5.25, accuracy: 1e-9)
    }

    func testClockDirections() {
        XCTAssertEqual(Fmt.clock(relativeDeg: 0), String(localized: "12 o'clock"))
        XCTAssertEqual(Fmt.clock(relativeDeg: 90), String(localized: "3 o'clock"))
        XCTAssertEqual(Fmt.clock(relativeDeg: -30), String(localized: "11 o'clock"))
        XCTAssertEqual(Fmt.clock(relativeDeg: 359), String(localized: "12 o'clock"))
    }

    func testCompassNames() {
        XCTAssertTrue(Fmt.compass(0).hasPrefix(String(localized: "N")))
        XCTAssertTrue(Fmt.compass(-90).hasPrefix(String(localized: "W")))
    }
}
