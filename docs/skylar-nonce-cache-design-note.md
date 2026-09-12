# Skylar Nonce Cache Architecture & Shared Store Migration Design Note

**Document:** `docs/skylar-nonce-cache-design-note.md`  
**Date:** 2026-09-12  
**Phase:** Phase 3 — Skylar Core Logic  
**Status:** Approved Architecture Reference  

---

## 1. Context & Purpose

The Skylar Context Gateway enforces strict cryptographic replay protection on all inbound capability requests per Architecture Specification §6:
> *"The cache must survive process restart... An in-memory-only cache loses replay state on crash or forced stop."*

In Phase 3, this requirement is satisfied by `PersistentNonceCache` implementing the `NonceCache` interface on Android using durable, synchronous-commit storage (`SharedPreferences` with atomic disk writes).

This design note satisfies **Phase 3 Deliverable 4**:
> *"Design note describing how the nonce cache interface can later be swapped for a shared store if horizontal scaling ever becomes relevant."*

---

## 2. Current Architecture: `NonceCache` Interface

The replay protection contract is decoupled from its storage mechanism via a clean interface:

```kotlin
interface NonceCache {
    /** True if newly recorded (accept this nonce); false if already seen (replay — reject). */
    fun checkAndRecord(callerId: String, nonce: String, expiresAt: Long): Boolean
    fun purgeExpired(): Int
}
```

### 2.1 Key Design Invariants
1. **Compound Keying:** Every entry is keyed by `caller_id:nonce`. A nonce is never evaluated globally across callers, preventing cross-caller namespace collisions or denial-of-service attempts.
2. **Fail-Closed Semantics:** If the underlying store fails to durably persist the entry (disk error, timeout, or write conflict), the method returns `false` (replay detected/rejected).
3. **Bounded Lifetime:** Every entry carries an expiration timestamp `maxOf(expiresAt, now + ttl)`. Once `now > expiresAt`, the entry may be safely pruned because the envelope verifier independently rejects expired envelopes before checking the nonce cache.
4. **Process Restart Survival:** For single-node deployments on Android, synchronous commits guarantee that once `checkAndRecord()` returns `true`, the nonce survives immediate process crash or restart.

---

## 3. Horizontal Scaling Requirements

If the Skylar Gateway deployment topology evolves from a single-device Android host to a multi-instance gateway tier (e.g., active-active edge instances or redundant gateway nodes behind a load balancer), local `SharedPreferences` will no longer protect against cross-node replay attacks:

```
[Client] ──> Envelope(nonce=N1) ──> Gateway Instance A (Accepted & Recorded)
[Client] ──> Envelope(nonce=N1) ──> Gateway Instance B (Replay! But unseen by B if local cache)
```

To maintain the security invariant across $N$ gateway instances, the storage backend must transition to a **distributed, atomic, low-latency shared store**.

---

## 4. Shared Store Target Options

### 4.1 Option A: Distributed Key-Value Store (Redis / Valkey / Dragonfly)
- **Mechanism:** Atomic `SET key value NX PX ttl` (Set if Not Exists with millisecond expiration).
- **Suitability:** Highest performance for high-throughput distributed gateway nodes.
- **Atomic Primitive:**
  ```lua
  -- Atomic check-and-set in Redis/Valkey
  if redis.call("EXISTS", KEYS[1]) == 1 then
      return 0
  else
      redis.call("SET", KEYS[1], ARGV[1], "PX", ARGV[2])
      return 1
  end
  ```
- **Advantages:** Native TTL eviction eliminates background purge routines; sub-millisecond roundtrips; well-tested clustering.

### 4.2 Option B: Distributed SQL / Spanner / SQLite with Replication
- **Mechanism:** ACID table with `PRIMARY KEY (caller_id, nonce)` and conditional insert (`INSERT INTO nonce_cache (caller_id, nonce, expires_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING`).
- **Suitability:** Environments where relational durability and strict external consistency are prioritized over raw latency.

---

## 5. Migration Blueprint: Implementing `SharedNonceCache`

Because `SkylarCore` relies strictly on the `NonceCache` interface via constructor injection:

```kotlin
class SkylarCore(
    private val context: Context,
    private val config: SkylarConfig = SkylarConfig.DEFAULT,
    private val keyRegistry: KeyRegistry = InMemoryKeyRegistry(),
    private val nonceCache: NonceCache = PersistentNonceCache(context, config),
    ...
)
```

No alterations to `SkylarCore`, `EnvelopeVerifier`, `AuthorizationMatrix`, or the pipeline execution flow are required.

### 5.1 Proposed `RedisNonceCache` Implementation Pattern

```kotlin
class RedisNonceCache(
    private val client: RedisClient,
    private val defaultTtlMs: Long = 3600_000L
) : NonceCache {

    override fun checkAndRecord(callerId: String, nonce: String, expiresAt: Long): Boolean {
        val key = "skylar:nonce:$callerId:$nonce"
        val now = System.currentTimeMillis()
        val ttlMs = maxOf(expiresAt - now, defaultTtlMs)

        return try {
            // SETNX with TTL: atomic operation in Redis
            val setResult = client.set(
                key,
                expiresAt.toString(),
                SetArgs().nx().px(ttlMs)
            )
            // Returns true only if the key was set (did not already exist)
            setResult == "OK"
        } catch (e: Exception) {
            // Guiding Principle #1: Scoped fail-closed on storage communication failure
            Logger.e("SharedNonceCache", "Redis communication failure — failing closed", e)
            false
        }
    }

    override fun purgeExpired(): Int {
        // Redis natively evicts keys past PX ttl; returns 0 or active count
        return 0
    }
}
```

### 5.2 Network Partition & Latency Considerations (CAP Theorem)
- **Consistency over Availability:** Under network partition between a gateway instance and the shared store, the gateway **must fail closed**. Replays must never be permitted during partition events.
- **Timeout Budget:** The distributed check must complete well within the gateway's pipeline timeout budget (`targetTimeoutMs = 10_000ms`, typically targeting < 20ms for the nonce check). If the store fails to respond within the allotted budget, the request is rejected with `REPLAY_CHECK_TIMEOUT`.

---

## 6. Conclusion
The Phase 3 `NonceCache` interface cleanly decouples decision logic from storage semantics. Transitioning from local durable storage to a distributed shared store is purely an adapter substitution, preserving all security guarantees without impacting the core gateway pipeline.
