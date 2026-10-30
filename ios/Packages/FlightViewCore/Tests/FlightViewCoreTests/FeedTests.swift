@testable import FlightViewCore
import XCTest

/// Mirrors android/app/src/test/.../FeedParsersTest.kt and the merge test in CoreTest.kt.
final class FeedTests: XCTestCase {
    func testReadsb() {
        let json = """
        {"now": 1793376336.002, "aircraft":[
          {"hex":"ab00d6","flight":"DAL85 ","r":"N808NW","t":"A333","alt_baro":34000,"alt_geom":35525,"gs":503.4,
           "track":288.29,"geom_rate":0,"lat":49.987015,"lon":-6.013208,"seen_pos":0.027},
          {"hex":"401eb4","flight":"GBHZH ","alt_baro":1600,"gs":84.0,"lat":50.553569,"lon":-4.841901,"seen_pos":1.511},
          {"hex":"400001","alt_baro":"ground","lat":50.1,"lon":-5.1},
          {"hex":"nothex","lat":1,"lon":1},
          {"hex":"400002"}
        ]}
        """
        let list = FeedParsers.readsb(Data(json.utf8), source: "adsbfi", fetchedAtS: 0)
        XCTAssertEqual(list.map(\.id), ["ab00d6", "401eb4", "400001"])
        XCTAssertEqual(list[0].callsign, "DAL85")
        XCTAssertEqual(list[0].altGeomM!, 35525 * 0.3048, accuracy: 1e-9)
        XCTAssertEqual(list[0].positionTime, 1793376336.002 - 0.027, accuracy: 1e-6)
        XCTAssertNil(list[1].altGeomM)
        XCTAssertTrue(list[2].onGround)
        XCTAssertNil(list[2].altBaroM)
    }

    func testOpenSky() {
        let json = """
        {"time":1793376331,"states":[
          ["407b7f","GCLYP   ","United Kingdom",1793376330,1793376330,-5.2451,50.286,883.92,false,34.68,212.28,0.33,null,1005.84,"1747",false,0],
          ["badrow"]
        ]}
        """
        let list = FeedParsers.openSky(Data(json.utf8), source: "opensky")
        XCTAssertEqual(list.count, 1)
        XCTAssertEqual(list[0].callsign, "GCLYP")
        XCTAssertEqual(list[0].groundSpeedKt!, 34.68 / 0.514444, accuracy: 1e-6)
        XCTAssertFalse(list[0].onGround)
    }

    func testMergeKeepsFreshestAndFillsGaps() {
        func plane(_ id: String, _ t: Double, lat: Double, type: String?, source: String) -> Aircraft {
            Aircraft(id: id, callsign: nil, registration: nil, typeCode: type, typeName: nil, operator: nil,
                     latDeg: lat, lonDeg: -5, altGeomM: 9000, altBaroM: nil, groundSpeedKt: 400, trackDeg: 90,
                     verticalRateFpm: 0, positionTime: t, onGround: false, category: nil, squawk: nil, sources: [source])
        }
        let merged = AircraftMerge.merge([
            [plane("abc123", 100, lat: 50.0, type: "A320", source: "adsblol"), plane("def456", 100, lat: 51, type: nil, source: "adsblol")],
            [plane("abc123", 102, lat: 50.01, type: nil, source: "adsbfi")],
        ])
        XCTAssertEqual(merged.map(\.id), ["abc123", "def456"])
        XCTAssertEqual(merged[0].latDeg, 50.01)
        XCTAssertEqual(merged[0].typeCode, "A320")
        XCTAssertEqual(merged[0].sources, ["adsbfi", "adsblol"])
    }
}
