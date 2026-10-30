package com.nayproductions.flightview.core

/** Framework-free scene checks shared by JUnit and the plain-JVM harness. */
object SceneChecks {
    /** Observer on Carn Brea, near Redruth, holding the phone upright facing north. */
    val observer = GeoPoint(50.2213, -5.2468, 230.0)
    val facingNorth = Mat3.rows(
        doubleArrayOf(1.0, 0.0, 0.0),
        doubleArrayOf(0.0, 0.0, -1.0),
        doubleArrayOf(0.0, 1.0, 0.0),
    )
    const val W = 390.0
    const val H = 844.0
    const val NOW = 1_800_000_000.0

    fun plane(id: String, dLat: Double, dLon: Double, altM: Double, onGround: Boolean = false, age: Double = 2.0) =
        Aircraft(
            id = id, callsign = id.uppercase(), registration = null, typeCode = "A320", typeName = null,
            operator = null, latDeg = observer.latDeg + dLat, lonDeg = observer.lonDeg + dLon,
            altGeomM = altM, altBaroM = null, groundSpeedKt = 420.0, trackDeg = 90.0, verticalRateFpm = 0.0,
            positionTime = NOW - age, onGround = onGround, category = "A3", squawk = null,
        )

    fun sampleScene(): List<SkyMarker> {
        val k = Intrinsics.aspectFill(65.0, 4.0 / 3.0, W, H)
        val planes = listOf(
            // ~24, 30 and 35 km away at 17-21 degrees elevation: all inside a 65 degree portrait FOV.
            plane("near", 0.20, 0.0, 9000.0),
            plane("left", 0.25, -0.03, 10000.0),
            plane("right", 0.30, 0.04, 11000.0),
            plane("far", 1.5, 0.0, 11000.0),
            plane("ground", 0.02, 0.0, 120.0, onGround = true),
            plane("old", 0.1, 0.01, 9000.0, age = 500.0),
        )
        return SkyScene.build(planes, observer, NOW, facingNorth, k, W, H)
    }

    fun sceneFiltering(): List<String> {
        val f = mutableListOf<String>()
        val ids = sampleScene().map { it.aircraft.id }
        if ("far" in ids) f += "aircraft beyond 80 km should be dropped"
        if ("ground" in ids) f += "aircraft on the ground should be dropped"
        if ("old" in ids) f += "aircraft with very old positions should be dropped"
        if (ids.firstOrNull() != "near") f += "scene should be sorted nearest first, got $ids"
        val near = sampleScene().first { it.aircraft.id == "near" }
        if (!near.screen.onScreen) f += "aircraft ~22 km north at 9 km should be on screen when facing north"
        if (near.screen.y >= H / 2) f += "aircraft above the horizon should appear above screen centre"
        return f
    }

    fun labelsDoNotOverlap(): List<String> {
        val anchors = (0 until 30).map { Triple("p$it", 180.0 + (it % 3) * 6.0, 300.0 + (it % 5) * 3.0) }
        val sizes = anchors.associate { it.first to LabelSize(110.0, 34.0) }
        val placed = LabelLayout.place(anchors, sizes, W, H)
        val f = mutableListOf<String>()
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            val a = placed[i]
            val b = placed[j]
            val sep = a.left + a.width <= b.left || b.left + b.width <= a.left ||
                a.top + a.height <= b.top || b.top + b.height <= a.top
            if (!sep) f += "labels ${a.id} and ${b.id} overlap"
        }
        if (placed.isEmpty()) f += "at least one label should be placed"
        if (placed.any { it.top < 0 || it.top + it.height > H }) f += "labels must stay on screen"
        return f
    }
}
