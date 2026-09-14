# Phase 8 — Lane B (Public Edge-Gated) Ephemeral Caller Operational Guide

**Date:** 2026-09-14  
**Reference:** Architecture doc §3, §4, §5 & Infrastructure doc §4

---

## 1. Overview & Architecture

Lane B provides public edge-gated ingress for **ephemeral callers** (such as dynamic CI runners, serverless agents, or third-party webhooks) that cannot or should not join the private Tailscale mesh directly.

```
[ Ephemeral Caller ]
         │
         │ 1. HTTPS POST + Cloudflare Access Service Token
         ▼
[ Cloudflare Access Edge ]
         │
         │ 2. Edge Verification (CF-Access-Client-Id / Secret)
         ▼
[ Cloudflare Tunnel (cloudflared) ]
         │
         │ 3. Forward over tunnel to OCI VM localhost
         ▼
[ OCI Relay Forwarder ] ◄── (Blind, Opaque Proxy; strips/ignores identity headers)
         │
         │ 4. Forward over private Tailscale mesh
         ▼
[ Skylar Mesh Listener ] ──► [ Skylar Core ] ──► [ Capability Target ]
                                  │
                                  ├─ Verify Cryptographic Signature (Phase 6 key)
                                  ├─ Check Authorization Matrix & Scope
                                  ├─ Check Nonce Freshness
                                  └─ Record Full Audit Trail
```

---

## 2. Ephemeral Caller Credential Flow

Ephemeral callers require two distinct credential tiers:

### Tier 1: Edge Transport Credential (Cloudflare Access Service Token)
- Granted out-of-band by administrators.
- Headers:
  - `CF-Access-Client-Id`: e.g. `cf-client-ci-runner-01`
  - `CF-Access-Client-Secret`: `cf-secret-alpha-9921`
- **Security Invariant:** Grants reachability through the Cloudflare Tunnel only. Never confers authorization to invoke Skylar capabilities.

### Tier 2: Request-Signing Credential (Phase 6 OAuth2 Exchange)
- Obtained dynamically via OAuth2 client-credentials-style token exchange against the centralized `IssuerService`:
  ```http
  POST /oauth2/token
  Host: issuer.internal:8090
  Content-Type: application/json

  {
    "grant_type": "client_credentials",
    "client_id": "ephemeral-agent-42",
    "client_secret": "agent-secret-hash",
    "scope": "starlight.notify",
    "cf_access_identity": {
      "identityToken": "jwt.cf.token",
      "userEmail": "agent-42@service.cloud",
      "issuer": "https://team.cloudflareaccess.com",
      "audience": "skylar-edge-gateway-aud",
      "issuedAt": 1789400000000,
      "expiresAt": 1789400900000
    }
  }
  ```
- **Response:** Short-lived `IssuedSigningCredential` (TTL: 15 minutes, private key held in caller memory, public key registered in Skylar's `KeyRegistry`).

---

## 3. End-to-End Request Construction

1. The caller creates a canonical JSON payload for the target capability.
2. The caller formats and signs a `RequestEnvelope`:
   - `callerId`: registered client caller ID (e.g. `caller-ephemeral-ci-01`).
   - `capability`: e.g. `starlight.notify`.
   - `scope`: matching authorized scope.
   - `nonce`: freshly generated UUID / entropy string.
   - `timestamp`: current epoch millis.
   - `signature`: ECDSA P-256 (or Ed25519) signature over canonicalized envelope fields using the ephemeral private key.
3. The caller wraps the envelope into an HTTP POST request targeted at `https://gateway.skylar.inscopelabs.com/lane-b/request`:
   - Request Body: JSON-serialized envelope.
   - Headers:
     - `CF-Access-Client-Id`: `<service-token-id>`
     - `CF-Access-Client-Secret`: `<service-token-secret>`
     - `CF-Ray`: `<ray-id-for-tracing>`

---

## 4. Relay Forwarder Invariants & Security Contract

### 4.1 Opaque Proxying (V8.5)
- The Relay Forwarder on the OCI VM treats the incoming body strictly as an opaque byte stream.
- It does **not** decode the envelope, does **not** validate the signature, and does **not** mutate payload content.
- It forwards the exact payload bytes over TCP to the destination device on the Tailscale mesh.

### 4.2 Edge Gating (V8.2)
- If the incoming request lacks valid Cloudflare Access credentials, the Relay Forwarder (or Cloudflare Edge) rejects the request with HTTP `403 Forbidden` (`CF_ACCESS_DENIED`) before any connection is opened to the mesh.

### 4.3 Rate Limiting & DoS Shield
- Implements a rolling-window token bucket / connection limiter (default 120 req/min per caller key) to shield the mobile Android device from burst traffic.

### 4.4 Header Neutrality & Compromise Resistance (Deliverable 4)
- **Compromise of the Relay Forwarder cannot enlarge caller privileges.**
- Even if the Relay Forwarder is compromised or hostile and attempts to:
  1. Modify the capability (e.g. from `starlight.notify` to `device.wipe`),
  2. Modify parameters or scopes,
  3. Forge Access identity headers (`Cf-Access-Authenticated-User-Email`),
- **Outcome:** Skylar Core on the destination Android device performs cryptographic signature verification on the canonical envelope. Because the relay does not possess the caller's private signing key, any payload mutation corrupts the signature and results in immediate rejection (`403 INVALID_SIGNATURE`).
- Access-asserted headers are never consumed by Skylar Core for authorization decisions.

---

## 5. Audit Logging & Forensics (V8.6)

Every Lane B request arriving at Skylar Core generates a structured `AuditRecord`:
- `callerId`: attributes the registered caller identity from the cryptographic envelope.
- `capability`: the invoked capability name.
- `decision`: `ALLOW` or `DENY`.
- `reason`: verification outcome or policy evaluation result.
- `lane`: recorded as Lane B with optional `CF-Ray` correlation ID.
- `timestamp`: monotonic audit timestamp.
