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
}
