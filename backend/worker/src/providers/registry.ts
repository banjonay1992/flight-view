import { boundingBox, KM_PER_NM } from "../geo";
import type { Aircraft, AreaQuery, Env } from "../types";
import { openSkyAuthHeader, parseOpenSky } from "./opensky";
import { parseReadsb } from "./readsb";

export interface Provider {
  id: string;
  attribution: string;
  /** Whether the provider's published terms allow commercial use. */
  commercialUse: boolean;
  /** Minimum spacing between our calls, enforced globally by the RateGate Durable Object. */
  minIntervalMs: number;
  maxRadiusNm: number;
  /** Returns null when the provider is not configured (e.g. missing key). */
  request(area: AreaQuery, env: Env): Promise<Request | null>;
  parse(body: unknown, fetchedAtS: number): Aircraft[];
}

function radiusNm(area: AreaQuery, max: number): number {
  return Math.min(max, Math.ceil(area.radiusKm / KM_PER_NM));
}

function fixed(v: number) {
  return v.toFixed(4);
}

/** readsb "v2/point/{lat}/{lon}/{nm}" style mirrors. */
function pointProvider(
  id: string,
  base: string,
  attribution: string,
  commercialUse: boolean,
  minIntervalMs: number,
): Provider {
  return {
    id,
    attribution,
    commercialUse,
    minIntervalMs,
    maxRadiusNm: 250,
    async request(area, env) {
      const url = `${base}/v2/point/${fixed(area.latDeg)}/${fixed(area.lonDeg)}/${radiusNm(area, 250)}`;
      return new Request(url, { headers: { "User-Agent": env.USER_AGENT, Accept: "application/json" } });
    },
    parse: (body, t) => parseReadsb(body, id, t),
  };
}

export const PROVIDERS: Provider[] = [
  pointProvider(
    "adsblol",
    "https://api.adsb.lol",
    "ADSB.lol (ODbL 1.0)",
    true,
    // No published hard limit; stay polite.
    500,
  ),
  pointProvider(
    "airplaneslive",
    "https://api.airplanes.live",
    "airplanes.live",
    false,
    1100,
  ),
  pointProvider(
    "adsbone",
    "https://api.adsb.one",
    "ADSB One",
    false,
    1100,
  ),
  {
    id: "adsbfi",
    attribution: "adsb.fi open data",
    commercialUse: false,
    minIntervalMs: 1100,
    maxRadiusNm: 250,
    async request(area, env) {
      const url = `https://opendata.adsb.fi/api/v2/lat/${fixed(area.latDeg)}/lon/${fixed(area.lonDeg)}/dist/${radiusNm(area, 250)}`;
      return new Request(url, { headers: { "User-Agent": env.USER_AGENT, Accept: "application/json" } });
    },
    parse: (body, t) => parseReadsb(body, "adsbfi", t),
  },
  {
    id: "flyitaly",
    attribution: "FlyItalyADSB (CC BY-SA 4.0)",
    commercialUse: true,
    // Free tier: 30 requests per minute.
    minIntervalMs: 2100,
    maxRadiusNm: 250,
    async request(area, env) {
      if (!env.FLYITALY_API_KEY) return null;
      const url = `https://api.flyitalyadsb.com/api/aircraft/v2/lat/${fixed(area.latDeg)}/lon/${fixed(area.lonDeg)}/dist/${radiusNm(area, 250)}/`;
      return new Request(url, {
        headers: { "User-Agent": env.USER_AGENT, Accept: "application/json", "X-Api-Key": env.FLYITALY_API_KEY },
      });
    },
    parse: (body, t) => parseReadsb(body, "flyitaly", t),
  },
  {
    id: "opensky",
    attribution: "The OpenSky Network (opensky-network.org)",
    commercialUse: false,
    // Anonymous: 400 credits/day. With an account: 4,000/day. Cache sharing does the heavy lifting.
    minIntervalMs: 10_000,
    maxRadiusNm: 250,
    async request(area, env) {
      const b = boundingBox(area);
      const qs = new URLSearchParams({
        lamin: b.lamin.toFixed(3),
        lomin: b.lomin.toFixed(3),
        lamax: b.lamax.toFixed(3),
        lomax: b.lomax.toFixed(3),
      });
      const auth = await openSkyAuthHeader(env);
      return new Request(`https://opensky-network.org/api/states/all?${qs}`, {
        headers: { "User-Agent": env.USER_AGENT, Accept: "application/json", ...auth },
      });
    },
    parse: (body) => parseOpenSky(body, "opensky"),
  },
];

export function enabledProviders(env: Env): Provider[] {
  const wanted = new Set(
    (env.PROVIDERS || "")
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean),
  );
  const commercial = env.COMMERCIAL_MODE === "true";
  return PROVIDERS.filter((p) => wanted.has(p.id) && (!commercial || p.commercialUse));
}
