import type { AreaQuery } from "./types";

export const KM_PER_NM = 1.852;
export const FT_TO_M = 0.3048;
export const MPS_TO_KT = 1 / 0.514444;
export const MPS_TO_FPM = 196.850394;

/** Grid used for privacy and shared caching: 0.1 degree (~11 km north-south). */
export const TILE_DEG = 0.1;
export const MAX_RADIUS_KM = 150;
export const DEFAULT_RADIUS_KM = 80;

/**
 * Snap a user's position to the centre of its grid tile and widen the radius
 * so the whole requested circle is still covered. The precise location never
 * leaves this function, and everyone in the same tile shares one cache entry.
 */
export function coarsenArea(latDeg: number, lonDeg: number, radiusKm: number): AreaQuery {
  const lat = Math.min(89.95, Math.max(-89.95, (Math.floor(latDeg / TILE_DEG) + 0.5) * TILE_DEG));
  const wrapped = ((((lonDeg + 180) % 360) + 360) % 360) - 180;
  const lon = (Math.floor(wrapped / TILE_DEG) + 0.5) * TILE_DEG;
  const halfDiagonalKm =
    Math.hypot(TILE_DEG * 111.32, TILE_DEG * 111.32 * Math.cos((lat * Math.PI) / 180)) / 2;
  const requested = Math.min(MAX_RADIUS_KM, Math.max(5, radiusKm));
  // Bucket radius to 10 km so cache keys stay few.
  const r = Math.ceil((requested + halfDiagonalKm) / 10) * 10;
  return { latDeg: round(lat, 2), lonDeg: round(lon, 2), radiusKm: r };
}

/**
 * Upstream queries are made per region, not per tile: one call with a ~300 km radius
 * covers every user in a 2 degree cell, and each user's circle is cut out of the cached
 * region. With free feeds limited to ~1 request/s, this is what lets a handful of
 * requests per second cover a continent instead of a few towns.
 */
export const REGION_DEG = 2;

export function regionFor(area: AreaQuery): AreaQuery {
  const lat = Math.min(89, Math.max(-89, (Math.floor(area.latDeg / REGION_DEG) + 0.5) * REGION_DEG));
  const lon = (Math.floor((area.lonDeg + 180) / REGION_DEG) + 0.5) * REGION_DEG - 180;
  // Widest point of the cell is its equator-side edge.
  const edgeLat = Math.max(0, Math.abs(lat) - REGION_DEG / 2);
  const halfDiagonalKm =
    Math.hypot(REGION_DEG * 111.32, REGION_DEG * 111.32 * Math.cos((edgeLat * Math.PI) / 180)) / 2;
  const r = Math.ceil((halfDiagonalKm + MAX_RADIUS_KM + TILE_DEG * 111.32) / 10) * 10;
  return { latDeg: round(lat, 2), lonDeg: round(lon, 2), radiusKm: r };
}

export function round(v: number, dp: number): number {
  const f = 10 ** dp;
  return Math.round(v * f) / f;
}

export function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const toRad = Math.PI / 180;
  const dLat = (lat2 - lat1) * toRad;
  const dLon = (lon2 - lon1) * toRad;
  const a =
    Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * toRad) * Math.cos(lat2 * toRad) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371.0088 * Math.asin(Math.min(1, Math.sqrt(a)));
}

/** Bounding box (degrees) around a circle; used by OpenSky which takes boxes, not circles. */
export function boundingBox(area: AreaQuery) {
  const dLat = area.radiusKm / 111.32;
  const dLon = area.radiusKm / (111.32 * Math.max(0.01, Math.cos((area.latDeg * Math.PI) / 180)));
  return {
    lamin: Math.max(-90, area.latDeg - dLat),
    lamax: Math.min(90, area.latDeg + dLat),
    lomin: Math.max(-180, area.lonDeg - dLon),
    lomax: Math.min(180, area.lonDeg + dLon),
  };
}
