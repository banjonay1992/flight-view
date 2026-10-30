package com.nayproductions.flightview.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * Runs every golden case in shared/fixtures/flightmath_fixtures.json against
 * the Kotlin core and returns a list of human-readable failures.
 *
 * Kept free of any test framework so it can also be run from a plain `main`.
 */
object FixtureSuite {
    fun run(json: String): List<String> {
        val root = Json.parseToJsonElement(json).jsonObject
        val tol = root.obj("tolerance")
        val angTol = tol.d("angle_deg")
        val mTol = tol.d("metres")
        val pxTol = tol.d("pixels")
        val failures = mutableListOf<String>()
        fun check(name: String, actual: Double, expected: Double, eps: Double) {
            if (abs(actual - expected) > eps) failures += "$name: expected $expected got $actual"
        }

        root.arr("look_angles").forEachIndexed { i, el ->
            val c = el.jsonObject
            val o = c.arr("observer").doubles()
            val t = c.arr("target").doubles()
            val la = Geodesy.lookAngles(GeoPoint(o[0], o[1], o[2]), GeoPoint(t[0], t[1], t[2]))
            check("look[$i].az", la.azimuthDeg, c.d("azimuth_deg"), angTol)
            check("look[$i].el", la.elevationDeg, c.d("elevation_deg"), angTol)
            check("look[$i].range", la.slantRangeM, c.d("slant_range_m"), mTol)
        }

        root.arr("dead_reckon").forEachIndexed { i, el ->
            val c = el.jsonObject
            val inp = c.obj("input")
            val r = Geodesy.deadReckon(
                GeoPoint(inp.d("lat"), inp.d("lon"), inp.d("alt_m")),
                inp.dn("ground_speed_kt"), inp.dn("track_deg"), inp.dn("vertical_rate_fpm"), inp.d("dt_s"),
            )
            val e = c.arr("expected").doubles()
            check("dr[$i].lat", r.latDeg, e[0], 1e-9)
            check("dr[$i].lon", r.lonDeg, e[1], 1e-9)
            check("dr[$i].alt", r.heightM, e[2], mTol)
        }

        root.arr("projection").forEachIndexed { i, el ->
            val c = el.jsonObject
            val r = c.arr("r_dw").toMat3()
            val view = c.arr("view").doubles()
            val k = Intrinsics.aspectFill(c.d("fov_long_deg"), c.d("aspect"), view[0], view[1])
            val ek = c.arr("intrinsics").doubles()
            check("proj[$i].fx", k.fx, ek[0], pxTol)
            check("proj[$i].cx", k.cx, ek[2], pxTol)
            val d = c.arr("world_dir").doubles()
            val p = Projection.project(r, Vec3(d[0], d[1], d[2]), k, view[0], view[1])
            if (p.inFront != c.b("in_front")) failures += "proj[$i].inFront mismatch"
            if (p.onScreen != c.b("on_screen")) failures += "proj[$i].onScreen mismatch"
            if (p.inFront) {
                check("proj[$i].x", p.x, c.d("x"), pxTol)
                check("proj[$i].y", p.y, c.d("y"), pxTol)
            }
            check("proj[$i].edge", Projection.edgeAngleRad(r, Vec3(d[0], d[1], d[2])), c.d("edge_angle_rad"), 1e-9)
        }

        root.arr("rotate_about_up").forEachIndexed { i, el ->
            val c = el.jsonObject
            val got = Projection.rotateAboutUp(c.arr("r_dw").toMat3(), c.d("degrees")).toArray()
            val exp = c.arr("expected").toMat3().toArray()
            for (j in 0 until 9) check("rot[$i][$j]", got[j], exp[j], 1e-12)
        }

        root.arr("ios_reference_to_enu").forEachIndexed { i, el ->
            val c = el.jsonObject
            val got = Projection.referenceNwuToEnu(c.arr("r_device_to_ref").toMat3()).toArray()
            val exp = c.arr("expected_r_dw").toMat3().toArray()
            for (j in 0 until 9) check("ios[$i][$j]", got[j], exp[j], 1e-12)
        }
        return failures
    }

    private fun JsonObject.obj(k: String) = getValue(k).jsonObject
    private fun JsonObject.arr(k: String) = getValue(k).jsonArray
    private fun JsonObject.d(k: String) = getValue(k).jsonPrimitive.double
    private fun JsonObject.b(k: String) = getValue(k).jsonPrimitive.boolean
    private fun JsonObject.dn(k: String): Double? = getValue(k).let { if (it is JsonNull) null else it.jsonPrimitive.double }
    private fun JsonArray.doubles() = map { it.jsonPrimitive.double }
    private fun JsonArray.toMat3(): Mat3 {
        val rows = map { (it as JsonElement).jsonArray.doubles().toDoubleArray() }
        return Mat3.rows(rows[0], rows[1], rows[2])
    }
}
