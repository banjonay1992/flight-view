package com.nayproductions.flightview

import com.nayproductions.flightview.data.FeedParsers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeedParsersTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `parses adsb fi readsb json`() {
        // Trimmed from a real adsb.fi response over Cornwall.
        val json = obj(
            """{"now": 1793376336.002, "aircraft":[
              {"hex":"ab00d6","flight":"DAL85 ","r":"N808NW","t":"A333","desc":"AIRBUS A-330-300","ownOp":"DELTA AIR LINES INC",
               "alt_baro":34000,"alt_geom":35525,"gs":503.4,"track":288.29,"geom_rate":0,"squawk":"5605","category":"A5",
               "lat":49.987015,"lon":-6.013208,"seen_pos":0.027},
              {"hex":"401eb4","type":"mlat","flight":"GBHZH ","alt_baro":1600,"gs":84.0,"track":234.0,"baro_rate":0,
               "lat":50.553569,"lon":-4.841901,"seen_pos":1.511},
              {"hex":"400001","alt_baro":"ground","lat":50.1,"lon":-5.1},
              {"hex":"nothex","lat":1,"lon":1},
              {"hex":"400002"}
            ]}""",
        )
        val list = FeedParsers.readsb(json, "adsbfi", 0.0)
        assertEquals(listOf("ab00d6", "401eb4", "400001"), list.map { it.id })
        val dal = list[0]
        assertEquals("DAL85", dal.callsign)
        assertEquals(35525 * 0.3048, dal.altGeomM!!, 1e-9)
        assertEquals(1793376336.002 - 0.027, dal.positionTime, 1e-6)
        assertEquals(listOf("adsbfi"), dal.sources)
        assertNull(list[1].altGeomM)
        assertEquals(1600 * 0.3048, list[1].altBaroM!!, 1e-9)
        assertTrue(list[2].onGround)
        assertNull(list[2].altBaroM)
    }

    @Test
    fun `parses opensky state vectors`() {
        val json = obj(
            """{"time":1793376331,"states":[
              ["407b7f","GCLYP   ","United Kingdom",1793376330,1793376330,-5.2451,50.286,883.92,false,34.68,212.28,0.33,null,1005.84,"1747",false,0],
              ["badrow"]
            ]}""",
        )
        val list = FeedParsers.openSky(json, "opensky")
        assertEquals(1, list.size)
        val a = list[0]
        assertEquals("407b7f", a.id)
        assertEquals("GCLYP", a.callsign)
        assertEquals(1005.84, a.altGeomM!!, 1e-9)
        assertEquals(34.68 / 0.514444, a.groundSpeedKt!!, 1e-6)
        assertEquals(1793376330.0, a.positionTime, 0.0)
    }

    @Test
    fun `garbage yields nothing`() {
        assertTrue(FeedParsers.readsb(obj("""{"ac":"nope"}"""), "x", 0.0).isEmpty())
        assertTrue(FeedParsers.openSky(obj("""{"states":null}"""), "x").isEmpty())
    }
}
