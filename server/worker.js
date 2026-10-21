/*
 * Eko Aza licence server - Cloudflare Worker + D1.
 *
 * One job: validate one time activation codes and remember which phone
 * redeemed which code. It does not store game logins and it does not see
 * transfers - the app does all of that on the phone.
 *
 *   POST /api/redeem          {code, device_id}      -> {ok:true, license_id} | 404 | 409 | 410
 *   GET  /api/licence?device_id=...                  -> {licensed:true|false, ...}
 *   POST /api/admin/mint      {count, days}           -> {codes:[...]}   (Bearer ADMIN_TOKEN)
 *   GET  /api/health                                  -> {ok:true}
 *
 * Bindings: DB (D1 database). Secret: ADMIN_TOKEN.
 */

const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I, O, 0, 1

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text.trim().toUpperCase()));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function checksum(body) {
  let n = 0;
  for (const ch of body) n = (n * 31 + ch.charCodeAt(0)) % ALPHABET.length;
  return ALPHABET[n];
}

function randomChars(len) {
  const bytes = new Uint8Array(len);
  crypto.getRandomValues(bytes);
  let out = "";
  for (let i = 0; i < len; i++) out += ALPHABET[bytes[i] % ALPHABET.length];
  return out;
}

function makeCode() {
  const body = randomChars(12);
  return `EKO-${body.slice(0, 4)}-${body.slice(4, 8)}-${body.slice(8, 12)}${checksum(body)}`;
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    },
  });
}

async function readJson(request) {
  try {
    const text = await request.text();
    return text ? JSON.parse(text) : {};
  } catch (_) {
    return {};
  }
}

// simple in memory limiter, good enough to slow down a code guessing loop
const hits = new Map();
function rateLimited(ip) {
  const now = Date.now();
  const list = (hits.get(ip) || []).filter((t) => now - t < 60_000);
  list.push(now);
  hits.set(ip, list);
  if (hits.size > 5000) hits.clear();
  return list.length > 20;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const ip = request.headers.get("cf-connecting-ip") || "unknown";

    if (path === "/api/health") return json({ ok: true, service: "eko-licence" });

    if (path === "/api/licence" && request.method === "GET") {
      const device = url.searchParams.get("device_id") || "";
      if (!device) return json({ error: "device_id is needed" }, 400);
      const row = await env.DB.prepare(
        "SELECT id, used_at FROM codes WHERE device_id = ? AND used_at IS NOT NULL LIMIT 1"
      ).bind(device).first();
      if (row) {
        return json({ licensed: true, license_id: row.id, redeemed_at: row.used_at });
      }
      return json({ licensed: false });
    }

    if (path === "/api/redeem" && request.method === "POST") {
      if (rateLimited(ip)) return json({ error: "Too many tries. Try again in a minute." }, 429);
      const body = await readJson(request);
      const code = String(body.code || "").trim().toUpperCase();
      const device = String(body.device_id || "").trim();
      if (!code || !device) return json({ error: "Code and device are needed" }, 400);

      const key = await sha256Hex(code);
      const row = await env.DB.prepare(
        "SELECT id, expires_at, device_id, used_at FROM codes WHERE code_hash = ?"
      ).bind(key).first();

      if (!row) return json({ error: "Unknown code" }, 404);
      if (row.expires_at && Date.now() > row.expires_at) {
        return json({ error: "This code expired - ask for a new one" }, 410);
      }
      if (row.device_id) {
        if (row.device_id === device) {
          return json({ ok: true, license_id: row.id, redeemed_at: row.used_at });
        }
        return json({ error: "This code was already used on another phone" }, 409);
      }
      const now = Date.now();
      await env.DB.prepare(
        "UPDATE codes SET device_id = ?, used_at = ? WHERE code_hash = ? AND device_id IS NULL"
      ).bind(device, now, key).run();
      return json({ ok: true, license_id: row.id, redeemed_at: now });
    }

    if (path === "/api/admin/mint" && request.method === "POST") {
      const auth = request.headers.get("authorization") || "";
      if (!env.ADMIN_TOKEN || auth !== `Bearer ${env.ADMIN_TOKEN}`) {
        return json({ error: "Admin token needed" }, 401);
      }
      const body = await readJson(request);
      const count = Math.min(Number(body.count) || 1, 500);
      const days = Number(body.days) || 2;
      const note = String(body.note || "");
      const now = Date.now();
      const expires = now + days * 86_400_000;
      const made = [];
      const statements = [];
      for (let i = 0; i < count; i++) {
        const code = makeCode();
        const id = randomChars(16);
        made.push(code);
        statements.push(
          env.DB.prepare(
            "INSERT OR IGNORE INTO codes (code_hash, id, created_at, expires_at, device_id, used_at, note) VALUES (?,?,?,?,NULL,NULL,?)"
          ).bind(await sha256Hex(code), id, now, expires, note)
        );
      }
      await env.DB.batch(statements);
      return json({ codes: made });
    }

    return json({ error: "Not found" }, 404);
  },
};
