CREATE TABLE IF NOT EXISTS codes (
  code_hash TEXT PRIMARY KEY,
  id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  device_id TEXT,
  used_at INTEGER,
  note TEXT
);
CREATE INDEX IF NOT EXISTS idx_codes_device ON codes(device_id);
