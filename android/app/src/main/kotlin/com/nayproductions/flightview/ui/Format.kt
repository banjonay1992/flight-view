package com.nayproductions.flightview.ui

import java.util.Locale
import kotlin.math.roundToInt

/** Aviation convention: altitude in feet, speed in knots. Distance follows the locale (mi in US/UK). */
object Fmt {
    private val milesCountries = setOf("US", "GB", "LR", "MM")

    fun altitude(metres: Double): String {
        val ft = metres / 0.3048
        val rounded = if (ft >= 1000) (ft / 100).roundToInt() * 100 else (ft / 10).roundToInt() * 10
        return String.format(Locale.getDefault(), "%,d ft", rounded)
    }

    fun distance(metres: Double, locale: Locale = Locale.getDefault()): String =
        if (locale.country in milesCountries) {
            String.format(locale, "%.1f mi", metres / 1609.344)
        } else {
            String.format(locale, "%.1f km", metres / 1000)
        }

    fun speed(knots: Double): String = String.format(Locale.getDefault(), "%d kt", knots.roundToInt())

    fun compass(deg: Double): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val d = deg.mod(360.0)
        return "${names[((d / 45).roundToInt()) % 8]} (${d.roundToInt()}°)"
    }

    /** Relative bearing as a clock hour: 12 is straight ahead. */
    fun clockHour(relativeDeg: Double): Int {
        val h = (relativeDeg.mod(360.0) / 30).roundToInt() % 12
        return if (h == 0) 12 else h
    }
}
