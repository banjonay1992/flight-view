import { mergeAircraft } from "./merge";
import type { Provider } from "./providers/registry";
import type { Aircraft, AircraftResponse, AreaQuery, Env, ProviderOutcome } from "./types";

export const UPSTREAM_TIMEOUT_MS = 3500;

export interface Gate {
  acquire(provider: Provider): Promise<boolean>;
  report(provider: Provider, ok: boolean): Promise<void>;
}

/** Gate backed by the RateGate Durable Object (one instance per provider). */
export function durableGate(env: Env): Gate {
  const stub = (p: Provider) => env.RATE_GATE.get(env.RATE_GATE.idFromName(p.id));
  return {
    async acquire(p) {
      try {
        const res = await stub(p).fetch(`https://gate/acquire?interval=${p.minIntervalMs}`);
        return ((await res.json()) as { ok: boolean }).ok;
      } catch {
        return false; // fail closed: never risk exceeding a provider's limit
      }
    },
    async report(p, ok) {
      try {
        await stub(p).fetch(`https://gate/report?ok=${ok ? 1 : 0}`);
      } catch {
        /* best effort */
      }
    },
  };
}

/** Query every enabled provider in parallel and merge what comes back. */
export async function aggregate(
  area: AreaQuery,
  providers: Provider[],
  env: Env,
  gate: Gate,
  fetcher: typeof fetch = fetch,
  nowMs: () => number = Date.now,
): Promise<AircraftResponse> {
  const results = await Promise.all(
    providers.map(async (p): Promise<{ outcome: ProviderOutcome; aircraft: Aircraft[] }> => {
      const started = nowMs();
      const outcome = (status: ProviderOutcome["status"], count = 0, detail?: string) => ({
        provider: p.id,
        status,
        count,
        ms: nowMs() - started,
        ...(detail ? { detail } : {}),
      });
      try {
        const req = await p.request(area, env);
        if (!req) return { outcome: outcome("skipped", 0, "not configured"), aircraft: [] };
        if (!(await gate.acquire(p))) return { outcome: outcome("skipped", 0, "rate limited"), aircraft: [] };
        const res = await fetcher(req, { signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS) });
        if (!res.ok) {
          await gate.report(p, false);
          return { outcome: outcome("error", 0, `HTTP ${res.status}`), aircraft: [] };
        }
        const aircraft = p.parse(await res.json(), nowMs() / 1000);
        await gate.report(p, true);
        return { outcome: outcome("ok", aircraft.length), aircraft };
      } catch (err) {
        await gate.report(p, false);
        const detail = err instanceof Error && err.name === "TimeoutError" ? "timeout" : "fetch failed";
        return { outcome: outcome("error", 0, detail), aircraft: [] };
      }
    }),
  );

  const serverTime = nowMs() / 1000;
  const contributing = results.filter((r) => r.outcome.status === "ok" && r.outcome.count > 0);
  return {
    serverTime,
    area,
    aircraft: mergeAircraft(
      results.map((r) => r.aircraft),
      area,
      serverTime,
    ),
    providers: results.map((r) => r.outcome),
    attribution: providers.filter((p) => contributing.some((c) => c.outcome.provider === p.id)).map((p) => p.attribution),
  };
}
