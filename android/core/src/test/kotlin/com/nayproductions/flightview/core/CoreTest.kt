package com.nayproductions.flightview.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class CoreTest {
    private val fixtures: String by lazy {
        // Gradle runs tests with the module directory as the working directory.
        File("../../shared/fixtures/flightmath_fixtures.json").readText()
    }

    @Test
    fun `core matches the shared golden fixtures`() {
        val failures = FixtureSuite.run(fixtures)
        assertTrue(failures.isEmpty(), failures.take(20).joinToString("\n"))
    }

    @Test
    fun `scene keeps nearby airborne aircraft and drops ground or distant ones`() =
        SceneChecks.sceneFiltering().let { assertTrue(it.isEmpty(), it.joinToString("\n")) }

    @Test
    fun `labels never overlap`() =
        SceneChecks.labelsDoNotOverlap().let { assertTrue(it.isEmpty(), it.joinToString("\n")) }

    @Test
    fun `hit test picks the nearest marker within radius`() {
        val markers = SceneChecks.sampleScene()
        val target = markers.first { it.screen.onScreen }
        val hit = SkyScene.hitTest(markers, target.screen.x + 5, target.screen.y - 5)
        assertEquals(target.aircraft.id, hit?.aircraft?.id)
        assertNull(SkyScene.hitTest(markers, -500.0, -500.0))
    }

    @Test
    fun `smoother snaps on large jumps and eases small ones`() {
        val s = ScreenSmoother()
        s.smooth("a", 100.0, 100.0, 0.0)
        val (x1, _) = s.smooth("a", 110.0, 100.0, 1.0 / 60.0)
        assertTrue(x1 > 100.0 && x1 < 110.0)
        val (x2, _) = s.smooth("a", 600.0, 100.0, 2.0 / 60.0)
        assertEquals(600.0, x2)
    }

    @Test
    fun `merge keeps the freshest position and fills gaps from other feeds`() {
        val base = SceneChecks.plane("abc123", 0.2, 0.0, 9000.0).copy(sources = listOf("adsblol"))
        val newer = base.copy(latDeg = base.latDeg + 0.01, positionTime = base.positionTime + 2, typeCode = null, sources = listOf("adsbfi"))
        val other = SceneChecks.plane("def456", 0.3, 0.0, 9000.0)
        val merged = AircraftMerge.merge(listOf(listOf(base, other), listOf(newer)))
        assertEquals(listOf("abc123", "def456"), merged.map { it.id })
        val m = merged.first()
        assertEquals(newer.latDeg, m.latDeg)
        assertEquals("A320", m.typeCode)
        assertEquals(listOf("adsbfi", "adsblol"), m.sources)
    }
}
