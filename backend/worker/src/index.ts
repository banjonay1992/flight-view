import { aggregate, durableGate } from "./aggregate";
import { verifySupabaseToken } from "./auth";
import { coarsenArea, DEFAULT_RADIUS_KM, regionFor } from "./geo";
import { withinArea } from "./merge";
import { enabledProviders } from "./providers/registry";
import type { AircraftResponse, Env } from "./types";

export { RateGate } from "./rateGate";

/** Live data is shared per region for this long. Aircraft move ~1 km in 4 s; the apps dead-reckon the gap. */
const FRESH_TTL_S = 4;
/** If every provider is busy or down, serve the last good answer for the region for up to this long. */
const STALE_TTL_S = 90;

const SECURITY_HEADERS: Record<string, string> = {
  "Content-Type": "application/json; charset=utf-8",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "Cache-Control": "no-store",
};

function json(body: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...SECURITY_HEADERS, ...extra } });
}

function problem(status: number, title: string, detail?: string): Response {
  return json({ type: "about:blank", title, status, ...(detail ? { detail } : {}) }, status);
}

function parseNumber(v: string | null): number | null {
  if (v === null || v.trim() === "") return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (request.method !== "GET") return problem(405, "Method not allowed");

    if (url.pathname === "/v1/health") {
      return json({ ok: true, providers: enabledProviders(env).map((p) => p.id) });
    }

    if (url.pathname !== "/v1/aircraft") return problem(404, "Not found");

    // Authentication (anonymous Supabase users are fine; it lets us rate-limit per install).
    let subject = request.headers.get("CF-Connecting-IP") ?? "unknown";
    if (env.REQUIRE_AUTH === "true") {
      const userId = await verifySupabaseToken(request.headers.get("Authorization"), env);
      if (!userId) return problem(401, "Unauthorised", "A valid Supabase access token is required.");
      subject = `user:${userId}`;
    }
    if (env.USER_RATE_LIMITER) {
      const { success } = await env.USER_RATE_LIMITER.limit({ key: subject });
      if (!success) return problem(429, "Too many requests", "Slow down and retry shortly.");
    }

    const lat = parseNumber(url.searchParams.get("lat"));
    const lon = parseNumber(url.searchParams.get("lon"));
    const radius = parseNumber(url.searchParams.get("radius_km")) ?? DEFAULT_RADIUS_KM;
    if (lat === null || lon === null || Math.abs(lat) > 90 || Math.abs(lon) > 180 || radius <= 0) {
      return problem(400, "Bad request", "lat, lon and optional radius_km are required.");
    }

    // Privacy: everything below works on the coarse tile, never the exact position.
    const area = coarsenArea(lat, lon, radius);
    const region = regionFor(area);
    const key = `${region.latDeg}/${region.lonDeg}`;
    const cache = caches.default;
    const freshKey = new Request(`https://cache.flightview.internal/fresh/${key}`);
    const staleKey = new Request(`https://cache.flightview.internal/stale/${key}`);

    const respond = (regional: AircraftResponse, state: "HIT" | "MISS" | "STALE") =>
      json(
        { ...regional, area, aircraft: withinArea(regional.aircraft, area) } satisfies AircraftResponse,
        200,
        { "X-Cache": state },
      );

    const hit = await cache.match(freshKey);
    if (hit) return respond(await hit.json(), "HIT");

    const providers = enabledProviders(env);
    const result = await aggregate(region, providers, env, durableGate(env));
    const anyOk = result.providers.some((p) => p.status === "ok");

    if (!anyOk) {
      const stale = await cache.match(staleKey);
      if (stale) return respond(await stale.json(), "STALE");
      if (result.providers.every((p) => p.status === "skipped")) {
        return problem(503, "Busy", "All live feeds are rate limited for this area. Retry in a second.");
      }
      return problem(502, "Upstream unavailable", "No live aircraft feed responded.");
    }

    const body = JSON.stringify(result);
    const store = (ttl: number) =>
      new Response(body, { headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${ttl}` } });
    ctx.waitUntil(Promise.all([cache.put(freshKey, store(FRESH_TTL_S)), cache.put(staleKey, store(STALE_TTL_S))]));
    return respond(result, "MISS");
  },
} satisfies ExportedHandler<Env>;
