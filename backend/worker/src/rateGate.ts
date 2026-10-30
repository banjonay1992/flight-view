/**
 * One Durable Object instance per upstream provider. It is the single place
 * that decides whether we may call that provider right now, so the free
 * feeds' published limits (typically 1 request/second) hold globally across
 * every Worker isolate, not just per isolate.
 *
 * It also acts as a circuit breaker: after repeated failures the provider is
 * rested with exponential backoff instead of being hammered.
 */
export class RateGate implements DurableObject {
  private lastCallAt = 0;
  private consecutiveFailures = 0;
  private openUntil = 0;

  constructor(
    private readonly state: DurableObjectState,
    _env: unknown,
  ) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const now = Date.now();

    if (url.pathname === "/acquire") {
      const interval = Number(url.searchParams.get("interval") ?? "1000");
      if (now < this.openUntil) return json({ ok: false, reason: "circuit-open" });
      if (now - this.lastCallAt < interval) return json({ ok: false, reason: "rate" });
      this.lastCallAt = now;
      return json({ ok: true });
    }

    if (url.pathname === "/report") {
      if (url.searchParams.get("ok") === "1") {
        this.consecutiveFailures = 0;
        this.openUntil = 0;
      } else {
        this.consecutiveFailures += 1;
        if (this.consecutiveFailures >= 3) {
          const backoff = Math.min(300_000, 15_000 * 2 ** (this.consecutiveFailures - 3));
          this.openUntil = now + backoff;
        }
      }
      return json({ ok: true });
    }

    return new Response("not found", { status: 404 });
  }
}

function json(body: unknown) {
  return new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
}
