--[[
  request_rate_limiter.lua — Token Bucket Rate Limiter
  =====================================================
  Implements the Token Bucket algorithm atomically inside Redis using EVAL.
  Because this entire script runs as a single Redis command, it is inherently
  race-condition-free even under extreme concurrent load — no distributed lock
  or optimistic retry needed.

  ┌──────────────────────────────────────────────────────────────────┐
  │  Token Bucket Model                                              │
  │                                                                  │
  │  • Bucket starts full (capacity = burst_capacity).              │
  │  • Each request consumes 1 token.                               │
  │  • Tokens refill continuously at `refill_rate` tokens/second.   │
  │  • Bucket is capped at `burst_capacity` (no overflow).          │
  │  • Request is ALLOWED  if tokens ≥ 1 before consumption.        │
  │  • Request is REJECTED if tokens < 1 (bucket empty).            │
  └──────────────────────────────────────────────────────────────────┘

  KEYS
  ────
  KEYS[1]  — tokens key   e.g. "rl:user-42:/api/data:tokens"
  KEYS[2]  — timestamp key e.g. "rl:user-42:/api/data:ts"

  ARGV
  ────
  ARGV[1]  — refill_rate     (float, tokens per second)
  ARGV[2]  — burst_capacity  (integer, max bucket size)
  ARGV[3]  — now_ms          (integer, current Unix time in milliseconds)
  ARGV[4]  — requested       (integer, tokens consumed per request, always 1)

  RETURN
  ──────
  A three-element array:
    [1]  allowed          — 1 if request is permitted, 0 if rejected
    [2]  tokens_remaining — floor of remaining tokens after this request
    [3]  burst_capacity   — the configured maximum (for X-RateLimit-Limit header)

  TTL Strategy
  ────────────
  The key expires after (burst_capacity / refill_rate) * 2 seconds.
  This guarantees the bucket is always GC'd within two full-refill cycles
  of inactivity, preventing unbounded key accumulation in Redis.
--]]

local tokens_key    = KEYS[1]
local timestamp_key = KEYS[2]

local refill_rate    = tonumber(ARGV[1])   -- tokens per second (can be fractional)
local burst_capacity = tonumber(ARGV[2])   -- maximum bucket size
local now_ms         = tonumber(ARGV[3])   -- current time in milliseconds
local requested      = tonumber(ARGV[4])   -- tokens requested (always 1)

-- ── Compute TTL ──────────────────────────────────────────────────────────────
-- Give at least 1 second to avoid zero TTL on very high-rate buckets.
local ttl = math.max(1, math.ceil((burst_capacity / refill_rate) * 2))

-- ── Fetch current state ──────────────────────────────────────────────────────
local last_tokens = tonumber(redis.call("GET", tokens_key))
local last_ts     = tonumber(redis.call("GET", timestamp_key))

-- ── Bootstrap on first request ───────────────────────────────────────────────
-- A brand-new bucket starts full. The timestamp is set to now so the first
-- refill delta is 0 (no free tokens on the very first call).
if last_tokens == nil then
    last_tokens = burst_capacity
end
if last_ts == nil then
    last_ts = now_ms
end

-- ── Calculate token refill ───────────────────────────────────────────────────
-- elapsed is in seconds (convert from ms delta)
local elapsed    = math.max(0, (now_ms - last_ts) / 1000.0)
local new_tokens = math.min(burst_capacity, last_tokens + (elapsed * refill_rate))

-- ── Consume token ────────────────────────────────────────────────────────────
local allowed     = 0
local result_tokens = new_tokens

if new_tokens >= requested then
    result_tokens = new_tokens - requested
    allowed = 1
end

-- ── Persist state with TTL ───────────────────────────────────────────────────
-- Both keys share the same TTL so they are always GC'd together.
redis.call("SETEX", tokens_key,    ttl, result_tokens)
redis.call("SETEX", timestamp_key, ttl, now_ms)

-- ── Return result ─────────────────────────────────────────────────────────────
return { allowed, math.floor(result_tokens), burst_capacity }
