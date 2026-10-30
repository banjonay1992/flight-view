package com.nayproductions.flightview.core

/**
 * Merges reports of the same aircraft from several feeds (the Worker and the feeds the
 * phone queries directly). Mirrors backend/worker/src/merge.ts.
 *
 * The freshest position wins, because feeds lag each other by a few seconds; any field
 * the freshest report lacks (type, registration, operator...) is filled from the others.
 */
object AircraftMerge {
    fun merge(lists: List<List<Aircraft>>): List<Aircraft> {
        val byId = LinkedHashMap<String, MutableList<Aircraft>>()
        for (list in lists) for (a in list) byId.getOrPut(a.id) { mutableListOf() } += a
        return byId.values.map { reports ->
            reports.sortByDescending { it.positionTime }
            reports.drop(1).fold(reports.first()) { best, r ->
                best.copy(
                    callsign = best.callsign ?: r.callsign,
                    registration = best.registration ?: r.registration,
                    typeCode = best.typeCode ?: r.typeCode,
                    typeName = best.typeName ?: r.typeName,
                    operator = best.operator ?: r.operator,
                    altGeomM = best.altGeomM ?: r.altGeomM,
                    altBaroM = best.altBaroM ?: r.altBaroM,
                    groundSpeedKt = best.groundSpeedKt ?: r.groundSpeedKt,
                    trackDeg = best.trackDeg ?: r.trackDeg,
                    verticalRateFpm = best.verticalRateFpm ?: r.verticalRateFpm,
                    category = best.category ?: r.category,
                    squawk = best.squawk ?: r.squawk,
                    sources = (best.sources + r.sources).distinct().sorted(),
                )
            }.let { it.copy(sources = it.sources.distinct().sorted()) }
        }.sortedBy { it.id }
    }
}
