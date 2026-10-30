import { describe, expect, it } from "vitest";
import { aggregate, type Gate } from "../src/aggregate";
import { boundingBox, coarsenArea, haversineKm, TILE_DEG } from "../src/geo";
import { mergeAircraft } from "../src/merge";
import { parseOpenSky } from "../src/providers/opensky";
import { parseReadsb } from "../src/providers/readsb";
import { enabledProviders, PROVIDERS } from "../src/providers/registry";
import type { Aircraft, Env } from "../src/types";

const env = {
  PROVIDERS: "adsblol,airplaneslive,adsbfi,adsbone,flyitaly,opensky",
  COMMERCIAL_MODE: "false",
  REQUIRE_AUTH: "false",
  SUPABASE_URL: "https://example.supabase.co",
  USER_AGENT: "FlightViewTest/1.0",
} as unknown as Env;

const NOW_S = 1_800_000_000;

function readsbBody(overrides: Record<string, unknown>[] = [], nowMs = NOW_S * 1000) {
  return {
    now: nowMs,
    ac: [
      {
        hex: "4ca7b5",
        flight: "RYR12AB ",
        r: "EI-DCL",
        t: "B738",
        desc: "BOEING 737-800",
        ownOp: "Ryanair",
        lat: 50.3,
        lon: -5.2,
        alt_baro: 35000,
        alt_geom: 35600,
        gs: 440.2,
        track: 82.1,
        baro_rate: -64,
        seen_pos: 1.5,
        category: "A3",
        squawk: "7271",
      },
      ...overrides,
    ],
  };
}

describe("coarsenArea", () => {
  it("snaps to a tile centre and still covers the requested circle", () => {
    const precise = { lat: 50.23456, lon: -5.23456 };
    const area = coarsenArea(precise.lat, precise.lon, 80);
    expect(area.latDeg).toBeCloseTo(50.25, 6);
    expect(area.lonDeg).toBeCloseTo(-5.25, 6);
    // Any point 80 km from the true position is within the widened radius of the tile centre.
    const offset = haversineKm(area.latDeg, area.lonDeg, precise.lat, precise.lon);
    expect(area.radiusKm).toBeGreaterThanOrEqual(80 + offset);
    expect(area.radiusKm % 10).toBe(0);
  });

  it("gives everyone in a tile the same key", () => {
    const a = coarsenArea(50.2001, -5.2999, 80);
    const b = coarsenArea(50.2999, -5.2001, 80);
    expect(a).toEqual(b);
  });

  it("wraps longitude and caps the radius", () => {
    // 181 E is 179 W, which sits in the tile spanning -179.0 .. -178.9
    const area = coarsenArea(10, 181, 9999);
    expect(area.lonDeg).toBeCloseTo(-179 + TILE_DEG / 2, 6);
    expect(coarsenArea(10, -180, 80).lonDeg).toBeCloseTo(-180 + TILE_DEG / 2, 6);
    expect(area.radiusKm).toBeLessThanOrEqual(160);
  });

  it("builds a bounding box that contains the circle", () => {
    const area = { latDeg: 50, lonDeg: -5, radiusKm: 100 };
    const b = boundingBox(area);
    expect(haversineKm(50, -5, b.lamax, -5)).toBeCloseTo(100, 0);
    expect(haversineKm(50, -5, 50, b.lomax)).toBeGreaterThanOrEqual(99);
  });
});

describe("parseReadsb", () => {
  it("normalises units and timestamps", () => {
    const [a] = parseReadsb(readsbBody(), "adsblol", NOW_S);
    expect(a.id).toBe("4ca7b5");
    expect(a.callsign).toBe("RYR12AB");
    expect(a.altGeomM).toBeCloseTo(35600 * 0.3048, 6);
    expect(a.altBaroM).toBeCloseTo(35000 * 0.3048, 6);
    expect(a.positionTime).toBeCloseTo(NOW_S - 1.5, 6);
    expect(a.verticalRateFpm).toBe(-64);
    expect(a.sources).toEqual(["adsblol"]);
  });

  it("accepts `aircraft` arrays and second-based `now`", () => {
    const body = { now: NOW_S, aircraft: readsbBody().ac };
    expect(parseReadsb(body, "adsbfi", 0)[0].positionTime).toBeCloseTo(NOW_S - 1.5, 6);
  });

  it("flags ground traffic and drops records without a position or valid hex", () => {
    const list = parseReadsb(
      readsbBody([
        { hex: "400001", lat: 50.1, lon: -5.1, alt_baro: "ground" },
        { hex: "400002", alt_baro: 1000 },
        { hex: "nothex", lat: 1, lon: 1 },
        { hex: "~2a0001", lat: 50.2, lon: -5.0, alt_baro: 1200 },
      ]),
      "adsblol",
      NOW_S,
    );
    expect(list.map((a) => a.id)).toEqual(["4ca7b5", "400001", "~2a0001"]);
    expect(list[1].onGround).toBe(true);
    expect(list[1].altBaroM).toBeNull();
  });

  it("survives garbage", () => {
    expect(parseReadsb(null, "x", NOW_S)).toEqual([]);
    expect(parseReadsb({ ac: "nope" }, "x", NOW_S)).toEqual([]);
  });
});

describe("parseOpenSky", () => {
  it("converts SI units to the app schema", () => {
    const body = {
      time: NOW_S,
      states: [["4ca7b5", "RYR12AB ", "Ireland", NOW_S - 3, NOW_S - 1, -5.2, 50.3, 10668, false, 226.3, 82, -0.3, null, 10850, "7271", false, 0]],
    };
    const [a] = parseOpenSky(body, "opensky");
    expect(a.groundSpeedKt).toBeCloseTo(226.3 / 0.514444, 3);
    expect(a.verticalRateFpm).toBeCloseTo(-0.3 * 196.850394, 3);
    expect(a.altGeomM).toBe(10850);
    expect(a.positionTime).toBe(NOW_S - 3);
    expect(a.callsign).toBe("RYR12AB");
  });

  it("handles empty state lists", () => {
    expect(parseOpenSky({ time: 1, states: null }, "opensky")).toEqual([]);
  });
});

describe("mergeAircraft", () => {
  const area = { latDeg: 50.25, lonDeg: -5.25, radiusKm: 90 };
  const base = parseReadsb(readsbBody(), "adsblol", NOW_S)[0];

  it("keeps the freshest position and fills gaps from other feeds", () => {
    const newerButSparse: Aircraft = {
      ...base,
      latDeg: 50.31,
      positionTime: base.positionTime + 2,
      registration: null,
      typeCode: null,
      typeName: null,
      operator: null,
      sources: ["opensky"],
    };
    const [m] = mergeAircraft([[base], [newerButSparse]], area, NOW_S + 2);
    expect(m.latDeg).toBe(50.31);
    expect(m.typeCode).toBe("B738");
    expect(m.registration).toBe("EI-DCL");
    expect(m.sources).toEqual(["adsblol", "opensky"]);
  });

  it("drops stale and out-of-area aircraft", () => {
    const stale = { ...base, id: "aaaaaa", positionTime: NOW_S - 500 };
    const far = { ...base, id: "bbbbbb", latDeg: 55 };
    const ids = mergeAircraft([[base, stale, far]], area, NOW_S).map((a) => a.id);
    expect(ids).toEqual(["4ca7b5"]);
  });
});

describe("providers", () => {
  it("enables every free provider by default", () => {
    expect(enabledProviders(env).map((p) => p.id).sort()).toEqual(PROVIDERS.map((p) => p.id).sort());
  });

  it("drops non-commercial feeds in commercial mode", () => {
    const ids = enabledProviders({ ...env, COMMERCIAL_MODE: "true" } as Env).map((p) => p.id);
    expect(ids.sort()).toEqual(["adsblol", "flyitaly"]);
  });

  it("builds the documented URLs", async () => {
    const area = { latDeg: 50.25, lonDeg: -5.25, radiusKm: 90 };
    const urls = Object.fromEntries(
      await Promise.all(PROVIDERS.map(async (p) => [p.id, (await p.request(area, env))?.url ?? null])),
    );
    expect(urls.adsblol).toBe("https://api.adsb.lol/v2/point/50.2500/-5.2500/49");
    expect(urls.airplaneslive).toBe("https://api.airplanes.live/v2/point/50.2500/-5.2500/49");
    expect(urls.adsbfi).toBe("https://opendata.adsb.fi/api/v2/lat/50.2500/lon/-5.2500/dist/49");
    expect(urls.flyitaly).toBeNull(); // no key configured
    expect(urls.opensky).toMatch(/^https:\/\/opensky-network\.org\/api\/states\/all\?lamin=/);
  });
});

describe("aggregate", () => {
  const area = { latDeg: 50.25, lonDeg: -5.25, radiusKm: 90 };
  const allowAll: Gate = { acquire: async () => true, report: async () => {} };

  it("merges successful feeds and reports failures without throwing", async () => {
    const fetcher = (async (input: RequestInfo) => {
      const url = typeof input === "string" ? input : input.url;
      if (url.includes("adsb.lol")) return Response.json(readsbBody());
      if (url.includes("airplanes.live"))
        return Response.json(readsbBody([{ hex: "400abc", lat: 50.4, lon: -5.0, alt_geom: 20000 }]));
      if (url.includes("adsb.fi")) return new Response("busy", { status: 429 });
      throw new TypeError("network down");
    }) as typeof fetch;

    const res = await aggregate(area, enabledProviders(env), env, allowAll, fetcher, () => NOW_S * 1000);
    expect(res.aircraft.map((a) => a.id)).toEqual(["400abc", "4ca7b5"]);
    expect(res.aircraft.find((a) => a.id === "4ca7b5")?.sources).toEqual(["adsblol", "airplaneslive"]);
    const status = Object.fromEntries(res.providers.map((p) => [p.provider, p.status]));
    expect(status).toMatchObject({ adsblol: "ok", airplaneslive: "ok", adsbfi: "error", flyitaly: "skipped" });
    expect(res.attribution.some((a) => a.includes("ODbL"))).toBe(true);
  });

  it("respects the gate", async () => {
    const denyAll: Gate = { acquire: async () => false, report: async () => {} };
    let calls = 0;
    const fetcher = (async () => {
      calls++;
      return Response.json(readsbBody());
    }) as unknown as typeof fetch;
    const res = await aggregate(area, enabledProviders(env), env, denyAll, fetcher, () => NOW_S * 1000);
    expect(calls).toBe(0);
    expect(res.providers.every((p) => p.status === "skipped")).toBe(true);
  });
});

describe("regionFor", () => {
  it("covers any user circle in the cell with one upstream query under the 250 nm cap", async () => {
    const { regionFor, MAX_RADIUS_KM } = await import("../src/geo");
    for (const [lat, lon] of [
      [0.01, 0.01],
      [50.23, -5.23],
      [-33.9, 151.2],
      [64.1, -21.9],
      [10, 179.99],
    ]) {
      const area = coarsenArea(lat, lon, MAX_RADIUS_KM);
      const region = regionFor(area);
      expect(region.radiusKm / 1.852).toBeLessThanOrEqual(250);
      // The farthest point of the user's circle from the region centre is inside the region query.
      const reach = haversineKm(region.latDeg, region.lonDeg, area.latDeg, area.lonDeg) + area.radiusKm;
      expect(reach).toBeLessThanOrEqual(region.radiusKm);
    }
  });

  it("puts neighbours in the same region", async () => {
    const { regionFor } = await import("../src/geo");
    expect(regionFor(coarsenArea(50.2, -5.2, 80))).toEqual(regionFor(coarsenArea(51.3, -4.1, 80)));
  });
});

describe("withinArea", () => {
  it("cuts a user's circle out of a region", async () => {
    const { withinArea } = await import("../src/merge");
    const base = parseReadsb(readsbBody(), "adsblol", NOW_S)[0];
    const far = { ...base, id: "cccccc", latDeg: 52.5 };
    expect(withinArea([base, far], { latDeg: 50.25, lonDeg: -5.25, radiusKm: 90 }).map((a) => a.id)).toEqual(["4ca7b5"]);
  });
});
