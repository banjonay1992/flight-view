package com.nayproductions.flightview

import com.nayproductions.flightview.data.AircraftApi
import com.nayproductions.flightview.ui.Fmt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class AppUnitTest {
    @Test
    fun `tile centre matches the server grid`() {
        val (lat, lon) = AircraftApi.tileCentre(50.23456, -5.23456)
        assertEquals(50.25, lat, 1e-9)
        assertEquals(-5.25, lon, 1e-9)
    }

    @Test
    fun `clock directions`() {
        assertEquals(12, Fmt.clockHour(0.0))
        assertEquals(3, Fmt.clockHour(90.0))
        assertEquals(11, Fmt.clockHour(-30.0))
        assertEquals(12, Fmt.clockHour(359.0))
    }

    @Test
    fun `distance follows locale`() {
        assertTrue(Fmt.distance(16093.44, Locale.UK).startsWith("10.0 mi"))
        assertTrue(Fmt.distance(10000.0, Locale.GERMANY).endsWith("km"))
    }
}
