#!/usr/bin/env node
/*
 * Eko Aza licence server.
 *
 * One job: validate one time activation codes and remember which phone
 * redeemed which code. It does not store game logins and it does not see
 * transfers - the app does all of that on the phone.
 *
 *   POST /api/redeem          {code, device_id}      -> {ok:true, license_id} | 404 | 409
 *   GET  /api/licence?device_id=...                  -> {licensed:true|false, ...}
 *   POST /api/admin/mint      {count, days}           -> {codes:[...]}   (admin token)
 *   GET  /api/health                                  -> {ok:true}
 *
 * Run:  ADMIN_TOKEN=secret node server.js
 * Env:  PORT (default 8787), DATA (default ./codes.json), ADMIN_TOKEN
 */
const http = require("http");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PORT || 8787);
const DATA = process.env.DATA || path.join(__dirname, "codes.json");
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || "";

const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I, O, 0, 1

function hashCode(code) {
  return crypto.createHash("sha256").update(code.trim().toUpperCase()).digest("hex");
}

function checksum(body) {
  // one character taken from the body itself, so typos are caught before the request
  let n = 0;
  for (const ch of body) n = (n * 31 + ch.charCodeAt(0)) % ALPHABET.length;
  return ALPHABET[n];
}

function makeCode() {
  const pick = (len) => {
    let out = "";
    const bytes = crypto.randomBytes(len);
    for (let i = 0; i < len; i++) out += ALPHABET[bytes[i] % ALPHABET.length];
    return out;
  };
  const body = pick(4) + pick(4) + pick(4);
  return `EKO-${body.slice(0, 4)}-${body.slice(4, 8)}-${body.slice(8, 12)}${checksum(body)}`;
}

function load() {
  try {
    return JSON.parse(fs.readFileSync(DATA, "utf8"));
  } catch (_) {
    return { codes: {} };
  }
}

function save(db) {
  const tmp = DATA + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify(db, null, 2));
  fs.renameSync(tmp, DATA);
}

const db = load();

// redeem attempts per ip, simple in memory limiter
const hits = new Map();
function rateLimited(ip) {
  const now = Date.now();
  const list = (hits.get(ip) || []).filter((t) => now - t < 60_000);
  list.push(now);
  hits.set(ip, list);
  return list.length > 20;
}

function json(res, code, body) {
  const text = JSON.stringify(body);
  res.writeHead(code, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(text),
    "Cache-Control": "no-store",
  });
  res.end(text);
}

function readBody(req) {
  return new Promise((resolve) => {
    let raw = "";
    req.on("data", (c) => {
      raw += c;
      if (raw.length > 64 * 1024) req.destroy();
    });
    req.on("end", () => {
      try {
        resolve(JSON.parse(raw || "{}"));
      } catch (_) {
        resolve({});
      }
    });
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  const ip = req.socket.remoteAddress || "unknown";

  if (url.pathname === "/api/health") return json(res, 200, { ok: true });

  if (url.pathname === "/api/licence" && req.method === "GET") {
    const device = url.searchParams.get("device_id") || "";
    if (!device) return json(res, 400, { error: "device_id is needed" });
    const found = Object.values(db.codes).find((c) => c.device_id === device);
    if (found) {
      return json(res, 200, {
        licensed: true,
        license_id: found.id,
        redeemed_at: found.used_at,
      });
    }
    return json(res, 200, { licensed: false });
  }

  if (url.pathname === "/api/redeem" && req.method === "POST") {
    if (rateLimited(ip)) return json(res, 429, { error: "Too many tries. Try again in a minute." });
    const body = await readBody(req);
    const code = String(body.code || "").trim().toUpperCase();
    const device = String(body.device_id || "").trim();
    if (!code || !device) return json(res, 400, { error: "Code and device are needed" });

    const key = hashCode(code);
    const row = db.codes[key];
    if (!row) return json(res, 404, { error: "Unknown code" });
    if (row.expires_at && Date.now() > row.expires_at) {
      return json(res, 410, { error: "This code expired - ask for a new one" });
    }
    if (row.device_id) {
      if (row.device_id === device) {
        return json(res, 200, { ok: true, license_id: row.id, redeemed_at: row.used_at });
      }
      return json(res, 409, { error: "This code was already used on another phone" });
    }
    row.device_id = device;
    row.used_at = Date.now();
    save(db);
    return json(res, 200, { ok: true, license_id: row.id, redeemed_at: row.used_at });
  }

  if (url.pathname === "/api/admin/mint" && req.method === "POST") {
    const auth = req.headers.authorization || "";
    if (!ADMIN_TOKEN || auth !== `Bearer ${ADMIN_TOKEN}`) {
      return json(res, 401, { error: "Admin token needed" });
    }
    const body = await readBody(req);
    const count = Math.min(Number(body.count) || 1, 500);
    const days = Number(body.days) || 2;
    const made = [];
    for (let i = 0; i < count; i++) {
      const code = makeCode();
      const id = crypto.randomBytes(8).toString("hex");
      db.codes[hashCode(code)] = {
        id,
        created_at: Date.now(),
        expires_at: Date.now() + days * 86_400_000,
        device_id: null,
        used_at: null,
        note: String(body.note || ""),
      };
      made.push(code);
    }
    save(db);
    return json(res, 200, { codes: made });
  }

  return json(res, 404, { error: "Not found" });
});

server.listen(PORT, () => {
  console.log(`eko licence server on :${PORT} data=${DATA}`);
  if (!ADMIN_TOKEN) console.log("WARNING: ADMIN_TOKEN is empty, /api/admin/mint is disabled");
});
