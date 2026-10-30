import { FT_TO_M } from "../geo";
import type { Aircraft } from "../types";

/**
 * Parser for the readsb / tar1090 "v2" JSON shape. ADSB.lol, airplanes.live,
 * adsb.fi, ADSB One and FlyItalyADSB all serve this format from community
 * receivers decoding raw 1090 MHz ADS-B and MLAT.
 */
interface ReadsbAircraft {
  hex?: string;
  flight?: string;
  r?: string;
  t?: string;
  desc?: string;
  ownOp?: string;
  lat?: number;
  lon?: number;
  alt_baro?: number | "ground";
  alt_geom?: number;
  gs?: number;
  track?: number;
  baro_rate?: number;
  geom_rate?: number;
  seen_pos?: number;
  seen?: number;
  category?: string;
  squawk?: string;
}

interface ReadsbResponse {
  ac?: ReadsbAircraft[];
  aircraft?: ReadsbAircraft[];
  now?: number;
}

const HEX = /^~?[0-9a-f]{6}$/i;

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function str(v: unknown): string | null {
  if (typeof v !== "string") return null;
  const s = v.trim();
  return s.length ? s : null;
}

export function parseReadsb(body: unknown, provider: string, fetchedAtS: number): Aircraft[] {
  if (!body || typeof body !== "object") return [];
  const res = body as ReadsbResponse;
  const list = Array.isArray(res.ac) ? res.ac : Array.isArray(res.aircraft) ? res.aircraft : [];
  // `now` is milliseconds on most mirrors and seconds on some; normalise.
  let nowS = num(res.now) ?? fetchedAtS;
  if (nowS > 1e12) nowS /= 1000;

  const out: Aircraft[] = [];
  for (const a of list) {
    const lat = num(a.lat);
    const lon = num(a.lon);
    if (!a.hex || !HEX.test(a.hex) || lat === null || lon === null) continue;
    if (Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
    const onGround = a.alt_baro === "ground";
    const altBaroFt = num(a.alt_baro);
    const altGeomFt = num(a.alt_geom);
    const age = num(a.seen_pos) ?? num(a.seen) ?? 0;
    out.push({
      // "~" prefixes non-ICAO (TIS-B) addresses; keep it so ids stay unique.
      id: a.hex.toLowerCase(),
      callsign: str(a.flight),
      registration: str(a.r),
      typeCode: str(a.t),
      typeName: str(a.desc),
      operator: str(a.ownOp),
      latDeg: lat,
      lonDeg: lon,
      altGeomM: altGeomFt === null ? null : altGeomFt * FT_TO_M,
      altBaroM: altBaroFt === null ? null : altBaroFt * FT_TO_M,
      groundSpeedKt: num(a.gs),
      trackDeg: num(a.track),
      verticalRateFpm: num(a.geom_rate) ?? num(a.baro_rate),
      positionTime: nowS - Math.max(0, age),
      onGround,
      category: str(a.category),
      squawk: str(a.squawk),
      sources: [provider],
    });
  }
  return out;
}
