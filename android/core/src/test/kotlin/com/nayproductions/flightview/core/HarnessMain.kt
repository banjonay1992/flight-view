package com.nayproductions.flightview.core

import java.io.File
import kotlin.system.exitProcess

/**
 * Plain-JVM entry point for environments without JUnit (used to verify the
 * core from the command line). Usage: HarnessMain <path-to-fixtures.json>
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "../../shared/fixtures/flightmath_fixtures.json"
    val failures = FixtureSuite.run(File(path).readText()) +
        SceneChecks.sceneFiltering() +
        SceneChecks.labelsDoNotOverlap()
    if (failures.isEmpty()) {
        println("Flight View core: all fixture and scene checks passed")
    } else {
        failures.take(40).forEach(::println)
        println("${failures.size} failure(s)")
        exitProcess(1)
    }
}
