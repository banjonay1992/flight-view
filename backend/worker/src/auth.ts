import { createRemoteJWKSet, jwtVerify } from "jose";
import type { Env } from "./types";

let jwks: ReturnType<typeof createRemoteJWKSet> | null = null;
let jwksFor = "";

/**
 * Verify a Supabase access token against the project's published JWKS
 * (asymmetric signing keys). Returns the user id, or null if invalid.
 * The apps sign in anonymously on first launch, so every request carries one.
 */
export async function verifySupabaseToken(authHeader: string | null, env: Env): Promise<string | null> {
  if (!authHeader?.startsWith("Bearer ")) return null;
  const token = authHeader.slice(7).trim();
  if (!token) return null;
  const base = env.SUPABASE_URL.replace(/\/$/, "");
  if (!jwks || jwksFor !== base) {
    jwks = createRemoteJWKSet(new URL(`${base}/auth/v1/.well-known/jwks.json`), { cacheMaxAge: 600_000 });
    jwksFor = base;
  }
  try {
    const { payload } = await jwtVerify(token, jwks, {
      issuer: `${base}/auth/v1`,
      audience: "authenticated",
    });
    return typeof payload.sub === "string" ? payload.sub : null;
  } catch {
    return null;
  }
}
