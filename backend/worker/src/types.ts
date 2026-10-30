export interface Env {
  /** Comma-separated provider ids to query. See src/providers/registry.ts. */
  PROVIDERS: string;
  /** "true" drops providers whose terms forbid commercial use. */
  COMMERCIAL_MODE: string;
  /** "true" requires a valid Supabase access token on /v1/* data routes. */
  REQUIRE_AUTH: string;
  SUPABASE_URL: string;
  /** Sent upstream so providers can contact us. Required etiquette for free feeds. */
  USER_AGENT: string;

  FLYITALY_API_KEY?: string;
  OPENSKY_CLIENT_ID?: string;
  OPENSKY_CLIENT_SECRET?: string;

  RATE_GATE: DurableObjectNamespace;
  /** Cloudflare Rate Limiting binding (per user / per IP). Optional in local dev. */
  USER_RATE_LIMITER?: RateLimit;
}

export interface RateLimit {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

/** The single aircraft shape returned to the apps. Units are in the field names. */
export interface Aircraft {
  id: string; // ICAO 24-bit address, lowercase hex
  callsign: string | null;
  registration: string | null;
  typeCode: string | null;
  typeName: string | null;
  operator: string | null;
  latDeg: number;
  lonDeg: number;
  altGeomM: number | null;
  altBaroM: number | null;
  groundSpeedKt: number | null;
  trackDeg: number | null;
  verticalRateFpm: number | null;
  /** Unix seconds when the position was measured. */
  positionTime: number;
  onGround: boolean;
  category: string | null;
  squawk: string | null;
  /** Provider ids that reported this aircraft in this response. */
  sources: string[];
}

export interface AreaQuery {
  latDeg: number;
  lonDeg: number;
  radiusKm: number;
}

export interface ProviderOutcome {
  provider: string;
  status: "ok" | "error" | "skipped";
  count: number;
  ms: number;
  detail?: string;
}

export interface AircraftResponse {
  serverTime: number;
  area: AreaQuery;
  aircraft: Aircraft[];
  providers: ProviderOutcome[];
  attribution: string[];
}
