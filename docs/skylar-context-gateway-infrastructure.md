# Skylar Context Gateway — Infrastructure Mapping & Build Plan (v3)

This document maps every abstract role in `skylar-context-gateway-architecture.md`
onto real apps, repos, and platforms, and breaks each one into a buildable
phase sequence. **Read the architecture doc for the security contract each
component must satisfy — this document only says who builds what, where.**

> v3 changes from v2:
> - Renamed throughout: "Nebulan" → **"Skylar Context Gateway"** (short
>   forms: "Skylar", "Skylar Core"). Naming only, no role or flow change.
> - `abx-server-1`'s application ID becomes `com.inscopelabs.abx.skylar`.
>   The repo path (`inscope-labs/abx-server-1`) is unchanged — internal
>   identifiers are intentionally decoupled from product branding.
> - §2: the ASCII flow diagram is **removed**. This document is mostly
>   for agentic consumption; a diagram is a second, harder-to-parse
>   representation of information the prose already states unambiguously
>   in §0–§1 of the architecture doc and §1 of this one. The diagram in
>   v2 was also ambiguous about whether persistent-caller traffic
>   routed through the OCI VM (it does not) — removing it removes that
>   ambiguity rather than fixing a drawing.
> - §3.1: request-signing credential bootstrap is now an explicit,
>   numbered, **blocking** build stage (new step 5), not just a flagged
>   risk — gates everything downstream of it in the suggested build
>   order (§5).
> - §3.2: Issuer placement decided as same-host-as-Relay-Forwarder for
>   this phase, with separate secret storage, documented residual risk.
>   The "prefer separate host / HSM/KMS" language from v2 is replaced
>   with this decision plus a note that separate-host remains a future
>   hardening step, not scheduled.
> - §4: risk list trimmed — request-signing credential bootstrapping is
>   no longer a risk to spike on; it is a scheduled, gating build stage
>   (§3.1 step 5). Issuer-colocation risk added explicitly, since it is
>   now an accepted trade-off rather than an open question.
> - §5: build order updated to insert the bootstrap stage before any
>   Lane B testing, and to reflect the Issuer's confirmed placement.

## 1. Role → Concrete Component Map

| Doc 1 Role | Concrete Component | Platform / Repo | Status today |
|---|---|---|---|
| Skylar Core | `abx-server-1` (Skylar Context Gateway), application ID `com.inscopelabs.abx.skylar` | Android app, `inscope-labs/abx-server-1` | Pure MCP enforcement runtime already scoped — validates/authorizes, never executes |
| Capability Target — UI execution | Starlight | Android app (separate install, UID-isolated), `inscope-labs/Starlight` | Governed workflow (Request Inbox → START WORKFLOW) exists; POC RPC transport built, not yet wired to Skylar |
| Capability Target — file execution | SFM (`abx-sfm-1`) | Android app (separate install, UID-isolated) | Vault + AIDL service design exists |
| Capability Target — plugin/script execution | xtools | Android — JS plugin runtime, XML/Fragment UI | Runtime Kernel + Bridge Contract + WebView Host + Security Foundation verified; already inherited JS bridge/Toolbox stack from abx-server-1 |
| Ingress Lane A (private mesh) | Embedded `libtailscale` inside `abx-server-1` | Official Tailscale C library (`tsnet` engine compiled via `gomobile bind` into an `.aar`/`.so` linked directly into Skylar's APK) | Not yet built; userspace-only, no VpnService, no system VPN slot. **Critical-path spike required.** Bundled official Tailscale app demoted to fallback only. |
| Ingress Lane B (public edge-gated) | Cloudflare Access + Cloudflare Tunnel (`cloudflared`) | Runs on the OCI VM — **not** on the phone | Not yet built; low-risk, mature server-side tooling |
| Relay Forwarder | OCI Always Free VM | `oci-relay-vm` — already a Tailscale mesh member, already documented as blind-forwarder-only | Provisioning engine built (via Bin-Box's OCI wizard); `cloudflared` + Access config not yet added |
| Issuer (credential issuance) | New minimal service, co-located on the OCI Relay VM | Same host as the Relay Forwarder role for this phase, separate secret storage, documented residual risk (see §3.2) | Not yet built |
| Bin-Box / RTX-100 | — | Explicitly **out of scope** — terminal/dev platform, not part of the automation execution path | Confirmed already: ABX integration deferred, future audit item |

## 2. End-to-End Flow (concrete)

**Lane A (persistent caller — John's device, dev laptop):** the caller enrolls once as a Tailscale mesh member via a silently-issued auth key, and separately receives a request-signing credential auto-issued at the moment that enrollment completes (architecture doc §5). From then on, every request travels directly over the private mesh to Skylar (`abx-server-1`) — it never passes through the OCI VM. Skylar's own mesh presence is the embedded `libtailscale` userspace node (§3.1); it holds a transport credential only and never asserts caller identity.

**Lane B (ephemeral caller — LLM agent, CI runner, third-party plugin dev):** the caller has no persistent mesh identity. It authenticates at Cloudflare Access (token/policy check) in front of a Cloudflare Tunnel (`cloudflared`), both running on the OCI Always Free VM. The VM, itself a Tailscale mesh member acting strictly as a Relay Forwarder, blind-forwards the caller's signed envelope onward over the private mesh to Skylar — unmodified, with any Access-asserted identity headers stripped or ignored. Before its first request, the caller separately completes the OAuth2 client-credentials-style flow against the Issuer (co-located on the same OCI VM, separate secret storage) to obtain its short-lived, scope-limited signing credential (architecture doc §5).

**At Skylar:** every request, from either lane, is verified (signature, replay/nonce, expiry, workflow-hash) independently of which lane it arrived by. Authorized requests are dispatched to the named Capability Target — Starlight, SFM, or xtools — over local IPC/AIDL only, since all three targets are co-resident on the same device as Skylar. No target is ever directly reachable by a caller; only Skylar Core, post-authorization, can invoke them, and each target's IPC endpoint enforces that at the platform level (signature permission or caller-UID check).

## 3. Per-Component Build Plan

### 3.1 Skylar Core (`abx-server-1`) — Android app
1. Implement the signed envelope (verify + canonicalize) as a shared library other repos can also consume for signing.
2. Build the `caller_id → {capability: scope}` authorization matrix store + default-deny check. The matrix itself is signed policy (versioned, revocable).
3. Build the signed, versioned routing table (`capability → target`) — starts hardcoded/local-signed, not remote-fetched, to avoid a bootstrap trust problem.
4. Build the nonce cache (start in-process with disk persistence across process restart; design the interface so it can move to a shared store later if Skylar is ever horizontally scaled — unlikely on-device, but keep the seam).
5. **Blocking stage — request-signing credential bootstrap.** Before any lane can be end-to-end tested (§5, steps 6–7), the two issuance paths from architecture doc §5 must exist and be exercised: (a) persistent-caller auto-issue triggered by completed Tailscale enrollment, and (b) the ephemeral-caller OAuth2 client-credentials-style flow against the Issuer (§3.2). Neither Lane A nor Lane B testing may proceed on mocked/stubbed credentials past this point.
6. Embed and manage the `libtailscale` mesh node lifecycle inside the Skylar process (userspace only). The node holds a distinct transport credential; key material is stored, rotated, and revoked separately from Skylar's envelope verification keys.
7. Wire AIDL dispatch to Starlight and SFM; wire the in-process/bridge call into xtools. Enforce platform-level access control on all local IPC surfaces (signature-level permissions or explicit caller-UID verification) so that only Skylar Core can invoke the endpoints.
8. Audit log sink (local, append-only file). Retention/PII policy and tamper-evidence beyond append-only are Deferred — post-MVP (architecture doc §10–§11); no further work on this item in this phase.
9. Per-target fail-closed scoping (§9 of architecture doc) — confirm a Starlight outage doesn't block SFM/xtools routing.

### 3.2 OCI VM — Relay Forwarder + Issuer host
1. Confirm existing Tailscale mesh membership (already provisioned via Bin-Box's OCI wizard — reuse, don't re-provision).
2. Install and configure `cloudflared`; register one Cloudflare Tunnel pointed at the VM's local forwarding port only (no other services exposed).
3. Configure Cloudflare Access policy in front of the tunnel (client entries for ephemeral callers, used as one factor in the OAuth2 client-credentials-style flow below).
4. Build/deploy the Issuer as a small standalone process **on this same VM**: mints Tailscale auth keys (via Tailscale's OAuth-client API) for Lane A transport credentials, mints request-signing credentials for both caller classes per architecture doc §5, and runs the OAuth2 client-credentials-style token exchange for ephemeral callers. **Issuer placement is decided, not open**: same host as the Relay Forwarder role, with the Issuer's signing key kept in separate secret storage from the Relay Forwarder's transport identity. This is an accepted residual risk for this phase — moving the Issuer to a separate host or an HSM/KMS is a future hardening step, not scheduled.
5. Lock the VM's Tailscale ACL tag so this node can reach Skylar's forwarding port only — no route to Starlight/SFM/xtools directly (architecture doc §8).
6. No caller-identity trust at this layer — confirm the forwarder passes envelopes opaquely and strips/ignores any Access-asserted headers before forwarding.

### 3.3 Starlight — Android app
1. Retire the POC's standalone `RpcServer` listener as a public transport — it becomes, at most, the *local* AIDL/IPC surface Skylar dispatches into, never independently network-reachable.
2. Protect the AIDL endpoint with platform-level access control (signature permission or explicit caller-UID check) so that only Skylar Core can invoke it.
3. Implement envelope verification using the shared library from §3.1 (or confirm Skylar has already verified before dispatch, and Starlight only checks freshness/hash locally as defense-in-depth).
4. Keep existing governed behavior intact: Request Inbox → user reviews → START WORKFLOW. Skylar authorizing a request must still not bypass user approval — Skylar's authorization and Starlight's user-consent gate are two separate checks, both required.

### 3.4 SFM (`abx-sfm-1`) — Android app
1. Expose the file-execution AIDL surface as a registered Capability Target (no changes to its UID isolation model).
2. Protect the AIDL endpoint with platform-level access control (signature permission or explicit caller-UID check) so that only Skylar Core can invoke it.
3. Confirm it never accepts a call that didn't originate from Skylar's dispatch path.

### 3.5 xtools — Android JS plugin runtime
1. Register as a Capability Target for scriptable/plugin capability names.
2. Protect any local IPC surface with platform-level access control so that only Skylar Core can invoke it.
3. Map its existing Verified/Pipeline-signed trust tiers onto Skylar's `capability → target` routing — a plugin's own signing tier is a second, narrower check *inside* xtools, after Skylar's authorization already passed.

## 4. Risks / Spikes to Run Before Committing

- **Embedded `libtailscale` / gomobile toolchain maturity** — critical-path spike required. Confirm that the official Tailscale C library can be compiled via `gomobile bind` into a stable `.aar`/`.so` that links cleanly into the Skylar APK, remains userspace-only (no VpnService, no system VPN slot), and correctly holds only a transport credential. Bundled official Tailscale app is retained solely as a fallback.
- **Cross-app envelope signing library** — four separate APKs (Skylar, Starlight, SFM, xtools) need to speak the exact same canonicalization/signature format; build this as one shared module pulled into each, not four independent implementations.
- **AIDL cross-UID dispatch under load** — confirm Skylar → Starlight/SFM AIDL calls behave correctly with the fail-closed-per-target model when one target is mid-update or force-stopped, and that platform-level permission checks reject non-Skylar callers.
- **Issuer/Relay Forwarder colocation** — accepted as a residual risk, not a design gap, but worth a specific spike: confirm the Issuer's secret storage is genuinely isolated from the Relay Forwarder's transport identity on the shared host (separate keystore/permissions, not just separate files in the same accessible directory), since this is the trade-off standing in for full host separation.

## 5. Suggested Build Order

1. Shared envelope-signing library (blocks everything else).
2. Critical-path spike: embed `libtailscale` via gomobile into `abx-server-1` and verify userspace mesh node lifecycle + key separation.
3. Skylar Core: authorization matrix + routing table + persistent nonce cache (local-only, no network yet).
4. Wire Skylar → Starlight/SFM/xtools over local AIDL/IPC with platform-level permission enforcement — validates the entire on-device half of the system without touching relay/transport at all.
5. OCI VM: Tailscale confirmation + `cloudflared` + Access + Issuer (co-located, separate secret storage per §3.2).
6. **Request-signing credential bootstrap, both caller classes** (§3.1 step 5) — blocking. Do not proceed to step 7 or 8 until a persistent caller can auto-receive a signing credential on enrollment, and an ephemeral caller can complete the OAuth2 client-credentials-style exchange and receive one.
7. Lane A end-to-end test (persistent caller → mesh → Skylar), now with real signing credentials, not mocked.
8. Lane B end-to-end test (ephemeral caller → Cloudflare → OCI VM → mesh → Skylar), now with real signing credentials, not mocked.
