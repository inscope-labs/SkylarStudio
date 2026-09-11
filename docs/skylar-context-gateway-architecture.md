# Skylar Context Gateway — Hardened Architecture Outline (v3)

Endpoint-agnostic: described entirely in terms of roles and routing, not
specific transport vendors or infrastructure. Any concrete transport
(mesh network, edge-gated public relay, or otherwise) is an
*implementation detail* that plugs into the roles below — none of it is
load-bearing for the security model.

> v3 changes from v2:
> - Renamed throughout: "Nebulan" → **"Skylar Context Gateway"** (short
>   forms: "Skylar", "Skylar Core"). Purely a naming change — no role,
>   flow, or security-model change accompanies it.
> - §5: request-signing credential bootstrapping **resolved**, no longer
>   an open problem. Persistent callers auto-issue on Tailscale
>   enrollment completion. Ephemeral callers use an OAuth2
>   client-credentials-style flow against the Issuer.
> - §5: Issuer placement **decided** — co-located on the same host as
>   the Relay Forwarder for this phase, with separate secret storage,
>   as a documented residual-risk acceptance (not the long-term target).
> - §7: local IPC access-control language unchanged from v2; the v2
>   changelog's citation of this content as "§11" was a typo — it has
>   always lived here. See v2 changelog for the affected entry.
> - §11: audit retention/PII policy, audit tamper-evidence mechanism,
>   HA/failover, and Issuer secret management are explicitly marked
>   **Deferred — post-MVP**, with no build steps in this phase.
> - §11: break-glass revocation mechanism **specified** (design only,
>   build steps still deferred): a system-wide kill switch plus
>   granular per-caller-class revocation.
> - §12: resolved/open lists updated accordingly.

## 0. Actors & Roles

| Role | Definition |
|---|---|
| **Caller** | Anything requesting a capability. Two identity classes: **persistent** (long-lived, individually provisioned) and **ephemeral** (short-lived, self-service issued). Identity class is a property of the credential, never of the transport it arrived over. |
| **Ingress Lane** | A transport path a request can arrive by. A lane provides reachability only — it asserts nothing about caller identity. |
| **Skylar Core** | The single policy enforcement point. Verifies, authorizes, routes. Never executes. |
| **Relay Forwarder** | An optional intermediate hop used by public-facing ingress lanes. Forwards opaque signed envelopes only. Holds no signing authority and no capability access of its own. |
| **Capability Target** | An execution-plane component (e.g., an accessibility-execution app, a file-execution vault, a plugin runtime) that Skylar may route an authorized request to. |

## 1. Ingress Lanes (transport-agnostic)

- **Lane A — private mesh**: caller has a persistent, individually enrolled network identity. Enrollment is transport-level reachability only, not authorization.
- **Lane B — public edge-gated**: caller has no persistent network identity. Reaches Skylar via an edge policy check + a Relay Forwarder that blind-forwards to Lane A's mesh.

Both lanes terminate at Skylar Core. **Neither lane's transport identity is ever treated as caller authorization.** Every request, regardless of lane, must carry its own signed envelope (§2) that Skylar verifies independently of how the bytes arrived.

**Note on Lane A implementation:** Skylar Core's own presence on the mesh is provided by an embedded userspace mesh node compiled into the same process (see infrastructure doc §1 and §3.1). The node holds a transport credential only. It does not act as a system VPN, does not intercept device-wide traffic, and does not assert caller identity. Transport remains an implementation detail; this note exists only so the security contract is unambiguous about what the embedded node is and is not.

## 2. Signed Request Envelope

Every request MUST carry:

| Field | Purpose |
|---|---|
| `caller_id` | Stable identifier, bound to a credential (§5), independent of transport/lane |
| `capability` | Requested capability name, matched against the routing policy (§4) |
| `params` | Capability-specific payload |
| `nonce` | Unique per request, used for replay protection (§6) |
| `issued_at` / `expires_at` | Bounds request validity |
| `workflow_hash` | Canonical hash binding `caller_id + capability + params + nonce + expires_at` together — prevents any field being altered independently of the others |
| `signature` | Over the canonicalized envelope, using `caller_id`'s registered signing key |

Canonical serialization and signature algorithm are fixed, singular, and versioned in the envelope itself (no per-caller variation). Any existing signing scheme already in use by a capability target is **superseded by this envelope**, not run in parallel — one scheme, adopted stack-wide.

## 3. Authorization Model

- **Default-deny.** A valid signature proves identity, not permission.
- Skylar holds a `caller_id → {capability: scope}` matrix. No caller may invoke a capability absent from its own scope entry.
- **The authorization matrix is signed policy**, versioned and revocable, same signing authority as the routing table (§4). Unsigned or out-of-band matrix edits are rejected. This prevents a compromised local store from silently granting permissions.
- Scopes are assigned at credential issuance (§5) and are as narrow as the caller's actual need — ephemeral callers get narrower, shorter-lived scopes than persistent ones by default.
- Quotas (rate/volume) are part of the scope, not a separate bolt-on. Concrete numbers per caller class remain open (§11).

## 4. Routing Policy

- The `capability → target` table is **signed policy**, versioned, and revocable — never resolved from caller-supplied input.
- Unknown or unlisted capability names are rejected before any dispatch attempt (default-deny, same posture as §3).
- Policy updates require the same signing authority as request envelopes — no unsigned or out-of-band routing changes.

## 5. Credential Model

Two credential types per caller, issued independently:

1. **Transport credential** — grants reachability on an ingress lane (e.g., mesh enrollment). Grants *nothing* else.
2. **Request-signing credential** — the key behind `caller_id` in §2. This is the actual authorization anchor.

A caller must hold both to complete a request, but they are never substitutable for each other — holding a transport credential never implies a valid signing credential, and vice versa. Both credential types are short-lived, individually revocable, and rotated on a defined cadence (persistent callers: longer-lived with rotation; ephemeral callers: single-use or very short TTL). Issuance authority is centralized to one issuer.

**Key separation is enforced even when both credentials live in the same process.** Skylar Core's own transport credential (the embedded mesh node key) and its envelope verification key are distinct material, stored separately, rotated separately, and revoked separately. Compromise of the mesh node key does not yield the ability to sign envelopes.

**Request-signing credential bootstrapping — resolved:**

- **Persistent callers** (John's devices, trusted dev laptops, the OCI VM itself): the signing credential is auto-issued the moment the device completes Tailscale mesh enrollment. Enrollment itself remains transport-only per §1 — this is a *separate*, second issuance step triggered by (not implied by) successful enrollment, so a device that only has mesh reachability and no signing credential yet still cannot produce a valid envelope.
- **Ephemeral callers** (LLM agents, CI runners, third-party plugin devs): an OAuth2 client-credentials-style flow. The caller authenticates to the Issuer using its Cloudflare Access-issued identity plus a pre-registered client entry (client ID/secret or equivalent, provisioned to that caller out of band before its first request); the Issuer validates both together and mints a short-lived, scope-limited signing credential. A Cloudflare Access token alone is never sufficient — it proves edge-policy admission, not authorization to receive a signing credential.

**Issuer placement — decided:** the Issuer runs on the same host as the Relay Forwarder role for this phase. This is accepted as a **documented residual risk**, not the long-term target: the Issuer's signing key and the Relay Forwarder's transport identity use separate secret storage on that host, but a full host compromise would expose both. Moving the Issuer to a separate host, or an HSM/KMS, remains a future hardening step, tracked but not scheduled.

## 6. Replay & Expiry Protection

- Nonce cache keyed by `caller_id + nonce`, TTL ≥ max envelope `expires_at` window + allowed clock skew.
- Cache lookups are atomic; if Skylar Core is ever horizontally scaled, the cache must be shared/consistent, not per-instance.
- **The cache must survive process restart** for at least the maximum `expires_at` window + skew. An in-memory-only cache loses replay state on crash or forced stop and reopens a replay window for every still-valid envelope. Persist to disk (or equivalent) with the same atomicity guarantees.
- Expired or already-seen nonces are rejected before authorization is even evaluated (cheapest check first).

## 7. Target Dispatch Model

Resolves the "no sockets, but how are remote targets reached" gap directly:

- **Co-resident targets** (on the same device as Skylar Core): reached via local IPC only (no network socket exists at all — this is the strict case).
- **Any target not co-resident**: must itself hold a transport credential and **dial out** to Skylar — Skylar never opens an unsolicited inbound connection to a target. This mirrors the same dial-out, blind-forward discipline already required of Lane B's Relay Forwarder. A target is never directly reachable by a caller under any circumstance — only by Skylar Core, post-authorization.
- **Local IPC surfaces are access-controlled.** Co-resident target IPC endpoints (AIDL services, bound services, etc.) MUST be protected so that only Skylar Core can invoke them. On Android this means signature-level permissions or explicit caller-UID verification. The IPC endpoint must reject any caller other than Skylar regardless of what the caller claims. "The target trusts Skylar" is not an enforcement mechanism; the platform-level check is.

## 8. Relay Forwarder Isolation

- Holds its own transport credential, scoped **only** to forward to Skylar Core — no route to any Capability Target, no signing authority, no authorization role.
- Must forward the signed envelope unmodified. Any identity signal asserted by an edge policy layer (headers, claims, etc.) is advisory only — Skylar Core verifies `signature` against `caller_id` itself and ignores forwarder-asserted identity entirely. A compromised or spoofed Relay Forwarder therefore cannot impersonate a caller; it can at most withhold or delay traffic.

## 9. Fail-Closed Scope

Fail-closed applies **per capability target**, not globally. An unreachable or failing target blocks only requests routed to *that* target — it must not degrade or halt routing to unrelated targets. Global fail-closed becomes a denial-of-service lever; scoped fail-closed keeps the security posture without that side effect.

## 10. Audit Logging

Each request logs, at minimum: `caller_id`, `capability`, decision (`allow`/`deny` + reason code), `nonce`, timestamp, and the envelope hash (not raw `params`, unless a capability explicitly requires payload-level audit). Log is append-only.

**Retention period, PII handling, and a tamper-evidence mechanism beyond "append-only file" are Deferred — post-MVP.** No build steps are scheduled for these in this phase; the append-only local log is the full extent of audit logging for now.

## 11. Operational Controls

- **Break-glass revocation — specified, build deferred.** Design: two mechanisms, both admin-triggered only. (1) A single system-wide kill switch that invalidates all Issuer-issued credentials at once — the simplest, most auditable starting posture. (2) Granular per-caller-class revocation (e.g., revoke all ephemeral credentials without touching persistent ones) for cases where a full kill switch is more disruptive than necessary. Both are design-level decisions recorded now; implementation is not scheduled for this phase.
- **Deferred — post-MVP, no build steps this phase:**
  - Audit log retention policy and PII handling (§10).
  - Audit log tamper-evidence mechanism beyond append-only (§10).
  - HA/failover story for Skylar Core itself and for the shared nonce cache (§6).
  - Secret management for the Issuer's own signing key and Skylar Core's own verification keys, beyond the same-host/separate-storage posture already accepted in §5.
- **Still to be filled in when scheduled:** concrete quota/rate-limit numbers per caller class (§3).

## 12. Explicitly Resolved vs. Still Open

**Resolved as of this pass:** request envelope shape, authorization-vs-authentication separation, authorization matrix as signed policy, routing-as-signed-policy, transport-credential-vs-signing-credential split, key separation within a shared process, replay protection mechanics including restart persistence, target dispatch model, local IPC access control, forwarder isolation, fail-closed scope, one unified signing scheme, request-signing credential issuance bootstrapping (both caller classes), Issuer placement (same-host, residual risk accepted), break-glass mechanism design.

**Still genuinely open (scheduling, not design):** audit retention/PII policy, audit tamper-evidence implementation, HA/failover design and implementation, secret storage hardening for the Issuer key beyond current posture, break-glass implementation, and the concrete quota/rate-limit numbers per caller class. All of these are explicitly deferred to post-MVP rather than unresolved by omission.
