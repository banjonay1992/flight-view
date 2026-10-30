import { haversineKm } from "./geo";
import type { Aircraft, AreaQuery } from "./types";

/** Positions older than this are dropped server-side; the apps fade anything over 30 s. */
export const MAX_POSITION_AGE_S = 120;

const FILLABLE: (keyof Aircraft)[] = [
  "callsign",
  "registration",
  "typeCode",
  "typeName",
  "operator",
  "altGeomM",
  "altBaroM",
  "groundSpeedKt",
  "trackDeg",
  "verticalRateFpm",
  "category",
  "squawk",
];

/**
 * Merge reports of the same aircraft from several feeds.
 *
 * The freshest position wins (feeds lag each other by a few seconds), and any
 * field the freshest report lacks is filled from the others, so a feed with
 * good metadata (type, registration) complements a feed with a newer fix.
 */
export function mergeAircraft(lists: Aircraft[][], area: AreaQuery, nowS: number): Aircraft[] {
  const byId = new Map<string, Aircraft[]>();
  for (const list of lists) {
    for (const a of list) {
      const bucket = byId.get(a.id);
      if (bucket) bucket.push(a);
      else byId.set(a.id, [a]);
    }
  }

  const merged: Aircraft[] = [];
  for (const reports of byId.values()) {
    reports.sort((x, y) => y.positionTime - x.positionTime);
    const best: Aircraft = { ...reports[0], sources: [] };
    const sources = new Set<string>();
    for (const r of reports) {
      r.sources.forEach((s) => sources.add(s));
      for (const key of FILLABLE) {
        if (best[key] === null && r[key] !== null) {
          (best as unknown as Record<string, unknown>)[key] = r[key];
        }
      }
      if (r.onGround && r.positionTime === best.positionTime) best.onGround = true;
    }
    best.sources = [...sources].sort();
    if (nowS - best.positionTime > MAX_POSITION_AGE_S) continue;
    if (haversineKm(area.latDeg, area.lonDeg, best.latDeg, best.lonDeg) > area.radiusKm) continue;
    merged.push(best);
  }
  merged.sort((a, b) => a.id.localeCompare(b.id));
  return merged;
}

/** Cut one user's circle out of a cached region. */
export function withinArea(aircraft: Aircraft[], area: AreaQuery): Aircraft[] {
  return aircraft.filter((a) => haversineKm(area.latDeg, area.lonDeg, a.latDeg, a.lonDeg) <= area.radiusKm);
}
