import { MPS_TO_FPM, MPS_TO_KT } from "../geo";
import type { Aircraft, Env } from "../types";

/**
 * OpenSky Network REST API. Works anonymously (400 credits/day) and with a
 * free account via OAuth2 client credentials (4,000/day; 8,000 if you feed).
 */
export const OPENSKY_TOKEN_URL =
  "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token";

let cachedToken: { value: string; expiresAt: number } | null = null;

export async function openSkyAuthHeader(env: Env, fetcher: typeof fetch = fetch): Promise<Record<string, string>> {
  if (!env.OPENSKY_CLIENT_ID || !env.OPENSKY_CLIENT_SECRET) return {};
  const now = Date.now();
  if (cachedToken && cachedToken.expiresAt - 60_000 > now) {
    return { Authorization: `Bearer ${cachedToken.value}` };
  }
  const res = await fetcher(OPENSKY_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "client_credentials",
      client_id: env.OPENSKY_CLIENT_ID,
      client_secret: env.OPENSKY_CLIENT_SECRET,
    }),
    signal: AbortSignal.timeout(3000),
  });
  if (!res.ok) return {}; // fall back to anonymous access
  const json = (await res.json()) as { access_token?: string; expires_in?: number };
  if (!json.access_token) return {};
  cachedToken = { value: json.access_token, expiresAt: now + (json.expires_in ?? 1800) * 1000 };
  return { Authorization: `Bearer ${cachedToken.value}` };
}

/** Test hook. */
export function resetOpenSkyToken() {
  cachedToken = null;
}

type StateVector = [
  string, // 0 icao24
  string | null, // 1 callsign
  string, // 2 origin_country
  number | null, // 3 time_position
  number, // 4 last_contact
  number | null, // 5 longitude
  number | null, // 6 latitude
  number | null, // 7 baro_altitude (m)
  boolean, // 8 on_ground
  number | null, // 9 velocity (m/s)
  number | null, // 10 true_track
  number | null, // 11 vertical_rate (m/s)
  unknown, // 12 sensors
  number | null, // 13 geo_altitude (m)
  string | null, // 14 squawk
  ...unknown[],
];

export function parseOpenSky(body: unknown, provider: string): Aircraft[] {
  if (!body || typeof body !== "object") return [];
  const states = (body as { states?: StateVector[] | null }).states;
  if (!Array.isArray(states)) return [];
  const out: Aircraft[] = [];
  for (const s of states) {
    if (!Array.isArray(s)) continue;
    const [icao, callsign, , timePos, lastContact, lon, lat, baro, onGround, vel, track, vr, , geo, squawk] = s;
    if (typeof icao !== "string" || typeof lat !== "number" || typeof lon !== "number") continue;
    const cs = typeof callsign === "string" ? callsign.trim() : "";
    out.push({
      id: icao.toLowerCase(),
      callsign: cs || null,
      registration: null,
      typeCode: null,
      typeName: null,
      operator: null,
      latDeg: lat,
      lonDeg: lon,
      altGeomM: typeof geo === "number" ? geo : null,
      altBaroM: typeof baro === "number" ? baro : null,
      groundSpeedKt: typeof vel === "number" ? vel * MPS_TO_KT : null,
      trackDeg: typeof track === "number" ? track : null,
      verticalRateFpm: typeof vr === "number" ? vr * MPS_TO_FPM : null,
      positionTime: typeof timePos === "number" ? timePos : lastContact,
      onGround: onGround === true,
      category: null,
      squawk: typeof squawk === "string" ? squawk : null,
      sources: [provider],
    });
  }
  return out;
}
