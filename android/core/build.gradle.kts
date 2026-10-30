// Pure JVM: the projection maths and scene logic, shared fixtures-tested, no Android dependencies.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Fixtures live at the repo root so iOS, Android and the reference share one file.
    inputs.file(rootProject.file("../shared/fixtures/flightmath_fixtures.json"))
}
