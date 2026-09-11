# Skylar Context Gateway — Phased Development Plan (Index)

**Version:** 1.1
**Date:** 2026-09-11
**Status:** Scaffold for implementation

> v1.1 changes from v1.0:
> - Renamed throughout: "Nebulan" / "Nebulan Gateway" → **"Skylar Context
>   Gateway"** ("Skylar" / "Skylar Core" as short forms). Naming only —
>   no phase content changes solely from this.
> - **New Phase 6 inserted**: "Request-Signing Credential Bootstrap."
>   In v1.0, credential bootstrapping was out of scope for Phase 5 and
>   listed as a still-open architecture item in the Post-Phase Notes.
>   The governing architecture/infrastructure docs have since resolved
>   this (persistent auto-issue on enrollment; ephemeral OAuth2
>   client-credentials-style flow) and made it an explicit **blocking**
>   stage — this plan now reflects that as its own phase, gating both
>   end-to-end phases rather than being deferred past them.
> - Former Phases 6, 7, 8 are renumbered **7, 8, 9** accordingly (phase
>   documents, dependencies, and validation-criteria IDs updated to
>   match).
> - Phase 5 (OCI Relay Forwarder + Issuer): Issuer key-isolation is no
>   longer framed as an open decision to make during the phase — it is
>   now a **settled decision** (same host as the Relay Forwarder for
>   this phase, separate secret storage, documented residual risk;
>   separate-host/HSM/KMS remains a future hardening item, not
>   scheduled). Phase 5's work items and validation criteria are
>   updated to confirm/implement that posture rather than choose it.
> - Phase 8 (formerly 7, Lane B end-to-end): request-signing credential
>   bootstrapping is no longer listed as an open architecture item out
>   of scope — it is exercised for real via Phase 6.
> - Phase 9 (formerly 8) Post-Phase Notes: request-signing credential
>   bootstrapping removed from the "remaining open items" list (now
>   resolved by Phase 6); break-glass revocation entry clarified as
>   "design specified, implementation deferred" rather than fully open.

This index organises the complete development programme for the Skylar Context Gateway (`abx-server-1`, application ID `com.inscopelabs.abx.skylar`) and its supporting infrastructure (OCI Always Free VM, Tailscale mesh, Cloudflare Access + `cloudflared`). The programme is divided into discrete, sequentially ordered phases. Each phase is documented in its own modular file to keep individual documents focused and of manageable size.

Every phase concludes with explicit **validation criteria**. A phase is considered complete only when those criteria have been demonstrated. Later phases depend on the successful validation of their predecessors.

## Phase Documents

| Phase | Document | Primary Focus |
|-------|----------|---------------|
| 0 | skylar-phase-00-prerequisites.md | Environment, repositories, tooling, and security prerequisites |
| 1 | skylar-phase-01-shared-envelope-library.md | Canonical signed-envelope library and shared policy artefacts |
| 2 | skylar-phase-02-libtailscale-spike.md | Critical-path embedding of userspace `libtailscale` |
| 3 | skylar-phase-03-skylar-core-logic.md | Authorization matrix, routing table, persistent nonce cache, audit sink |
| 4 | skylar-phase-04-on-device-target-integration.md | AIDL / local IPC wiring to Starlight, SFM, and xtools with platform permission enforcement |
| 5 | skylar-phase-05-oci-relay-issuer.md | OCI VM Relay Forwarder, `cloudflared`, Cloudflare Access, and Issuer service |
| 6 | skylar-phase-06-credential-bootstrap.md | **(New)** Request-signing credential issuance: persistent auto-issue and ephemeral OAuth2 client-credentials flow |
| 7 | skylar-phase-07-lane-a-end-to-end.md | Lane A (private mesh) end-to-end validation |
| 8 | skylar-phase-08-lane-b-end-to-end.md | Lane B (public edge-gated) end-to-end validation |
| 9 | skylar-phase-09-mailbox-test-harness.md | Scriptable mailbox endpoint app and final automated test suite |

## Governing References

- Architecture contract: `skylar-context-gateway-architecture.md`
- Infrastructure mapping: `skylar-context-gateway-infrastructure.md`
- Cross-document validation: `skylar-context-gateway-outline.md`

## Guiding Principles

1. **Security contract first.** Transport is never authorization. Every request is independently verified by Skylar Core.
2. **Default-deny.** Unsigned policy, unknown capabilities, and expired or replayed nonces are rejected.
3. **Key separation.** Transport credentials and request-signing credentials remain distinct even when co-resident.
4. **Scoped fail-closed.** Failure of one Capability Target must not affect unrelated targets.
5. **Validation gates.** No phase advances until its declared acceptance tests pass.
6. **Modular documentation.** Each phase file is self-contained for review, estimation, and hand-off.

## Overall Success Criterion

At the conclusion of Phase 9 the Skylar Context Gateway shall successfully execute a complete series of automated tests against the scriptable mailbox endpoint app across both ingress lanes, demonstrating correct envelope verification, authorization, routing, replay protection, credential issuance, and per-target isolation.

---

# Phase 0 — Prerequisites & Environment Preparation

**Phase ID:** 0
**Dependencies:** None
**Estimated duration:** 3–5 working days
**Validation gate:** All checklist items confirmed before Phase 1 begins

## 1. Objectives

Establish a stable, reproducible development and test environment for the Skylar Context Gateway programme. Confirm that all required repositories, toolchains, accounts, and security baselines are in place so that subsequent phases can proceed without environmental blockers.

## 2. Scope

### In Scope
- Repository access and branch strategy for `abx-server-1`, Starlight, SFM (`abx-sfm-1`), xtools, and any new shared library modules.
- Confirming/updating `abx-server-1`'s application ID to `com.inscopelabs.abx.skylar`.
- Android development toolchain (Kotlin, Android SDK, NDK where required for native binding).
- OCI Always Free VM access and existing Tailscale membership verification.
- Cloudflare account access for Tunnel and Access configuration (later phases).
- Tailscale account and API credentials suitable for auth-key issuance.
- Secure secret storage conventions (no secrets in source control).
- Baseline device and emulator fleet for multi-app AIDL testing.

### Out of Scope
- Implementation of any Skylar logic.
- Provisioning of new infrastructure (deferred to Phase 5).
- Credential issuance flows (deferred to Phase 6).

## 3. Deliverables

1. Confirmed access matrix for all required Git repositories.
2. Documented Android build environment (SDK/NDK versions, Gradle configuration notes).
3. Verification that the existing OCI VM is reachable via Tailscale and that its current ACL posture is recorded.
4. Inventory of required Cloudflare and Tailscale credentials (stored in approved secret manager only).
5. Confirmation that `abx-server-1`'s application ID is `com.inscopelabs.abx.skylar`.
6. Phase 0 completion checklist signed off by the technical lead.

## 4. Work Items

1. Clone / confirm access to all relevant repositories under the agreed organisation.
2. Establish a common Kotlin multi-module workspace skeleton if a shared library module is to be introduced in Phase 1.
3. Verify Android Studio / command-line build of the current `abx-server-1` baseline succeeds on at least one physical device and one emulator.
4. Confirm/update the application ID to `com.inscopelabs.abx.skylar`; confirm the change doesn't break the current build.
5. Confirm OCI VM SSH / Tailscale connectivity and capture current Tailscale node status and ACL tags.
6. Create (or confirm existence of) a dedicated secrets store entry for future Issuer and mesh keys.
7. Define naming conventions for capability names, caller identifiers, and policy version identifiers that will be used across all later phases.

## 5. Validation Criteria

| ID | Criterion | Evidence Required |
|----|-----------|-------------------|
| V0.1 | All primary repositories are accessible and buildable from a clean checkout | Build logs |
| V0.2 | OCI VM responds on Tailscale; current ACL tags documented | `tailscale status` output + ACL snapshot |
| V0.3 | Secret storage location and access policy documented | Written note in project wiki or equivalent |
| V0.4 | Device / emulator matrix for multi-UID testing identified | Device list with Android versions |
| V0.5 | Capability and caller naming conventions published | Short reference document or section in this plan |
| V0.6 | `abx-server-1` application ID confirmed as `com.inscopelabs.abx.skylar` | Manifest / build config inspection |

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Incomplete repository access | Blocks Phase 1 | Escalate access requests before starting coding work |
| OCI VM ACL already too permissive | Security exposure | Record and tighten in Phase 5; do not expand now |
| Missing Cloudflare / Tailscale admin rights | Delays Phase 5–8 | Request elevated rights in parallel with Phase 0 |
| Application ID change breaks existing signing/release config | Build or distribution disruption | Test the rename on a branch first; confirm Play listing implications before merging |

## 7. Exit Condition

Phase 0 is complete when the technical lead confirms that every validation criterion has been satisfied and the Phase 0 checklist is recorded. No code for the Skylar security contract may be committed until this gate is passed.

---

# Phase 1 — Shared Envelope & Policy Library

**Phase ID:** 1
**Dependencies:** Phase 0 complete
**Estimated duration:** 5–8 working days
**Validation gate:** Library unit tests and cross-module consumption demonstrated

## 1. Objectives

Produce a single, versioned Kotlin library that implements the canonical signed request envelope defined in the hardened architecture. The library must be consumable by Skylar Core, Starlight, SFM, xtools, and any future test harness so that all participants speak identical serialization, hashing, and signature formats.

## 2. Scope

### In Scope
- Canonical envelope data model (`caller_id`, `capability`, `params`, `nonce`, `issued_at`, `expires_at`, `workflow_hash`, `signature`).
- Deterministic canonical serialization and `workflow_hash` computation.
- Signature generation and verification against a registered public key.
- Version field inside the envelope so future algorithm changes remain backward-compatible.
- Minimal signed-policy container for the authorization matrix and the capability-to-target routing table.
- Unit tests covering happy path, tampering, expiry, and malformed input.

### Out of Scope
- Network transport of any kind.
- Persistent storage of policy or nonce state (handled in later phases).
- Issuer logic or key generation beyond test keys.

## 3. Deliverables

1. Shared Kotlin module (published as an internal AAR or source dependency) containing:
   - Envelope data classes and builders.
   - Canonicalization + hashing utilities.
   - Signature provider interface and at least one concrete implementation (e.g., Ed25519 or the algorithm selected by the project).
   - Policy artefact reader that rejects unsigned or incorrectly signed policy.
2. Comprehensive unit-test suite.
3. Short integration note describing how other repositories consume the module.
4. Test-key generation script (for development only; never for production Issuer keys).

## 4. Work Items

1. Define the exact byte layout / serialization format and freeze it under a version identifier.
2. Implement envelope construction, canonicalization, and `workflow_hash`.
3. Implement signature generation and verification.
4. Implement a minimal signed-policy container and verification routine.
5. Write unit tests that cover:
   - Valid envelope acceptance.
   - Signature failure on any field mutation.
   - Expiry rejection.
   - Replay of identical nonce (library level only; full cache comes later).
   - Policy signature rejection.
6. Publish the module so that a second repository can depend on it and successfully verify a signed envelope.

## 5. Validation Criteria

| ID | Criterion | Evidence Required |
|----|-----------|-------------------|
| V1.1 | Unit tests pass with ≥ 90 % coverage of the library public surface | Test report |
| V1.2 | A second module / repository can construct, sign, and verify an envelope using only the shared library | Cross-module demonstration |
| V1.3 | Any alteration of a signed envelope (capability, params, nonce, timestamps) causes verification failure | Automated test cases |
| V1.4 | Unsigned policy artefacts are rejected | Automated test cases |
| V1.5 | Envelope version field is present and checked | Code inspection + test |

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Non-deterministic serialization across platforms | Signature mismatches | Freeze a single canonical form and test it rigorously |
| Choice of signature algorithm later found unsuitable | Rework | Document selection rationale and keep the interface abstract |
| Accidental inclusion of production key material | Security incident | Test keys only; production keys remain outside this phase |

## 7. Exit Condition

Phase 1 is complete when the shared library is published, all validation criteria are satisfied, and at least one external consumer has demonstrated successful sign-and-verify interoperability.

---

# Phase 2 — Embedded libtailscale Critical-Path Spike

**Phase ID:** 2
**Dependencies:** Phase 0 complete (Phase 1 may proceed in parallel)
**Estimated duration:** 7–12 working days (high uncertainty)
**Validation gate:** Userspace mesh node starts, joins, and remains isolated inside the Skylar process

## 1. Objectives

Prove that the official Tailscale C / Go library (`tsnet` engine) can be compiled via `gomobile bind` (or an equivalent stable binding path) into a usable Android artefact, linked into `abx-server-1`, and operated purely in userspace. The resulting node must hold only a transport credential and must never assert caller identity or act as a system VPN.

This phase is explicitly a spike; its purpose is to de-risk the most technically uncertain component of Lane A before significant investment in Core logic.

## 2. Scope

### In Scope
- Investigation and implementation of the binding path (`gomobile`, direct CGO, or alternative).
- Minimal Kotlin / JNI wrapper that starts and stops a userspace Tailscale node.
- Demonstration that the node can join an existing Tailscale mesh using a short-lived auth key.
- Confirmation that no VpnService is required and that the system VPN slot remains free.
- Demonstration of key-material separation: the mesh node's transport key is stored and rotated independently of any future envelope verification keys.
- Fallback plan documentation (bundled official Tailscale app) should the embedded approach prove unviable.

### Out of Scope
- Full Skylar request handling.
- Production key management.
- Lane B components.

## 3. Deliverables

1. Working proof-of-concept APK (or instrumented `abx-server-1` branch) that embeds a userspace Tailscale node.
2. Spike report documenting:
   - Binding method chosen and rationale.
   - Observed stability, memory, and battery characteristics.
   - Any limitations discovered.
   - Clear go / no-go recommendation.
3. Fallback design note if embedding is judged unsuitable.

## 4. Work Items

1. Evaluate current Tailscale open-source build instructions for Android / gomobile compatibility.
2. Produce a minimal binding that exposes start / stop / status / auth-key injection.
3. Integrate the binding into a test build of `abx-server-1`.
4. Issue a short-lived Tailscale auth key and confirm the node appears in the mesh.
5. Verify that the process does not request or obtain the system VPN privilege.
6. Document storage location and rotation approach for the transport credential.
7. Stress-test node lifecycle (start, stop, process death, restart) and record results.
8. Produce the formal spike report and recommendation.

## 5. Validation Criteria

| ID | Criterion | Evidence Required |
|----|-----------|-------------------|
| V2.1 | Userspace node successfully joins the Tailscale mesh | `tailscale status` / coordination server view |
| V2.2 | No VpnService declaration or system VPN usage | Manifest inspection + runtime observation |
| V2.3 | Node can be started and stopped from Kotlin without process crash | Automated or scripted demonstration |
| V2.4 | Transport credential is distinct and independently rotatable | Code and configuration review |
| V2.5 | Spike report with go / no-go decision recorded | Written report |

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| gomobile / toolchain instability | Spike failure | Time-box the investigation; activate fallback early |
| Unexpected battery or memory cost | Product rejection | Measure and report; consider throttling strategies |
| Binding breaks on future Tailscale releases | Maintenance burden | Pin versions and document upgrade path |

## 7. Exit Condition

Phase 2 is complete when the spike report is accepted and either (a) the embedded node is demonstrated to meet the validation criteria, or (b) a documented decision is taken to proceed with the official Tailscale app as the Lane A transport mechanism. In the latter case, subsequent phase documents must be updated accordingly.

---

# Phase 3 — Skylar Core Logic (Authorization, Routing, Replay Protection)

**Phase ID:** 3
**Dependencies:** Phase 1 complete; Phase 2 go-decision preferred but not strictly blocking for pure logic work
**Estimated duration:** 8–12 working days
**Validation gate:** Local, network-free tests of verify → authorize → route → audit

## 1. Objectives

Implement the pure policy-enforcement core of Skylar inside `abx-server-1`. At the end of this phase Skylar shall be able to accept a signed envelope (via an in-process test harness), verify it, enforce the signed authorization matrix, resolve the capability against the signed routing table, reject replays and expired envelopes, and emit an audit record — without any network transport or real Capability Target being involved.

## 2. Scope

### In Scope
- Integration of the shared envelope library (Phase 1).
- Persistent nonce cache keyed by `caller_id + nonce` that survives process restart.
- Signed authorization matrix store (`caller_id → {capability: scope}`) with default-deny.
- Signed capability-to-target routing table.
- Basic audit-log sink (append-only local file).
- In-process test harness that feeds envelopes and asserts outcomes.
- Per-target fail-closed scaffolding (stubs that can later be replaced by real AIDL clients).

### Out of Scope
- Real AIDL dispatch (Phase 4).
- Mesh or Cloudflare transport (Phases 2, 5, 7, 8).
- Credential issuance (Phase 6).

## 3. Deliverables

1. Skylar Core modules:
   - Envelope verifier.
   - Nonce cache with disk persistence.
   - Authorization matrix evaluator.
   - Routing table resolver.
   - Audit logger.
2. In-process test suite covering allow, deny, replay, expiry, and unknown-capability cases.
3. Configuration mechanism for loading signed policy artefacts at startup.
4. Design note describing how the nonce cache interface can later be swapped for a shared store if horizontal scaling ever becomes relevant.

## 4. Work Items

1. Wire the shared envelope library into `abx-server-1`.
2. Implement the persistent nonce cache (SQLite or equivalent atomic store).
3. Implement loading and verification of signed authorization matrix and routing table.
4. Implement the decision pipeline: expiry → nonce check → signature verify → authorization → route.
5. Implement the append-only audit sink.
6. Create an in-process test harness that constructs signed envelopes with test keys and drives the pipeline.
7. Confirm that a simulated failure of one target stub does not prevent routing to another target stub.

## 5. Validation Criteria

| ID | Criterion | Evidence Required |
|----|-----------|-------------------|
| V3.1 | Valid envelope with permitted capability is accepted and routed to the correct stub | Test log |
| V3.2 | Envelope with unknown capability or insufficient scope is denied | Test log |
| V3.3 | Replay of a previously seen nonce is rejected even after process restart | Test that forces restart |
| V3.4 | Expired envelope is rejected before authorization is evaluated | Test log |
| V3.5 | Audit record contains caller_id, capability, decision, nonce, timestamp, envelope hash | Sample audit entries |
| V3.6 | Failure of one target stub leaves other stubs reachable | Isolation test |

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Nonce cache corruption on abrupt process death | Replay window | Use durable, atomic storage and test crash recovery |
| Policy loading race at startup | Incorrect decisions | Load and verify policy before accepting any requests |
| Overly complex scope language | Implementation delay | Start with simple capability presence + optional numeric quotas |

## 7. Exit Condition

Phase 3 is complete when the in-process test suite passes all validation criteria and the Core decision pipeline is ready to accept real local IPC clients in Phase 4.

---

# Phase 4 — On-Device Target Integration (AIDL / Local IPC)

**Phase ID:** 4
**Dependencies:** Phase 3 complete
**Estimated duration:** 8–14 working days
**Validation gate:** Skylar successfully dispatches authorized requests to Starlight, SFM, and xtools over protected local IPC

## 1. Objectives

Connect Skylar Core to the real Capability Targets (Starlight, SFM, xtools) using platform-enforced local IPC only. Every AIDL (or equivalent) surface must reject callers other than Skylar's UID / signature. Skylar itself continues to perform full verification and authorization before any dispatch occurs.

## 2. Scope

### In Scope
- Definition and protection of AIDL interfaces for Starlight and SFM.
- In-process or bridge registration for xtools.
- Platform-level access control (signature permission or explicit caller-UID verification) on every target surface.
- Skylar-side dispatch clients that invoke the protected interfaces only after a successful Core decision.
- Confirmation that Starlight's existing user-consent gate remains mandatory and is not bypassed by Skylar authorization.
- End-to-end on-device tests using real installed target apps (no network required).

### Out of Scope
- Mesh or Cloudflare transport.
- Changes to the internal execution logic of Starlight, SFM, or xtools beyond the IPC surface and permission model.

## 3. Deliverables

1. Protected AIDL service definitions and implementations for Starlight and SFM.
2. Permission / UID enforcement logic on each target.
3. Skylar dispatch adapters for all three targets.
4. Integration test suite that installs Skylar + targets on a device or emulator and exercises allow / deny / isolation scenarios.
5. Confirmation that a force-stopped target affects only its own capabilities.

## 4. Work Items

1. Design the minimal AIDL contracts required for each capability class.
2. Implement signature-level or UID-check protection on Starlight and SFM services.
3. Update Starlight so that its former public RPC surface becomes a private AIDL endpoint only.
4. Wire Skylar's routing table to the concrete AIDL clients.
5. Ensure xtools registration respects the same "only Skylar may invoke" rule.
6. Build and run multi-app integration tests on physical devices and emulators.
7. Verify that Starlight still requires explicit user approval after Skylar has authorized the request.
8. Document the exact permission model used so that future targets can adopt the same pattern.

## 5. Validation Criteria

| ID | Criterion | Evidence Required |
|----|-----------|-------------------|
| V4.1 | Authorized request reaches the correct target and produces an observable result | Device test log |
| V4.2 | Request from any non-Skylar UID is rejected by the target's AIDL surface | Negative test |
| V4.3 | Unauthorized capability (even with valid signature) is denied by Skylar before dispatch | Test log |
| V4.4 | Force-stopping Starlight leaves SFM and xtools capabilities functional | Isolation test |
| V4.5 | Starlight user-consent gate is still enforced | Manual or instrumented confirmation |
| V4.6 | Audit log records the final decision for both allow and deny cases | Audit sample |

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| AIDL binder identity checks behave differently across Android versions | Compatibility issues | Test on the project's declared minimum and target SDK levels |
| Target apps inadvertently expose additional surfaces | Security regression | Code review of exported components |
| Complex multi-process lifecycle races | Flaky tests | Prefer deterministic start / stop sequences in the test harness |

## 7. Exit Condition

Phase 4 is complete when the on-device integration test suite passes all validation criteria and Skylar can reliably dispatch to all three Capability Targets under the platform permission model. The system is now ready for network transport layers.

---

# Phase 5 — OCI Relay Forwarder, Cloudflared, Access & Issuer

**Phase ID:** 5
**Dependencies:** Phase 0 complete; Phase 2 preferred for mesh understanding
**Estimated duration:** 6–10 working days
**Validation gate:** Opaque envelope can be received via Cloudflare Tunnel and forwarded onto the Tailscale mesh toward Skylar; Issuer service is running with its key-isolation posture implemented

## 1. Objectives

Stand up the infrastructure components that realise Lane B and host credential issuance:
- Confirm and harden the existing OCI Always Free VM as a Tailscale mesh member acting solely as Relay Forwarder.
- Deploy `cloudflared` with a Cloudflare Tunnel that terminates only on the local forwarding port.
- Place Cloudflare Access (service-token policy) in front of the tunnel.
- Deploy the Issuer service, co-located on the same VM as the Relay Forwarder for this phase, with its signing key kept in storage separate from the Relay Forwarder's transport identity. This placement is a **settled decision** (documented residual risk; separate-host/HSM/KMS is a future hardening item, not scheduled here) — this phase implements that posture, it does not choose between options.

## 2. Scope

### In Scope
- Tailscale ACL tightening so the VM can reach only Skylar's designated forwarding port.
- Installation and configuration of `cloudflared`.
- Cloudflare Access application and service-token policy for ephemeral callers.
- Issuer process (or container) capable of issuing transport credentials (Tailscale auth keys, Cloudflare service tokens), with separate secret storage from the Relay Forwarder's own transport identity on the shared host.
- Confirmation that the Relay Forwarder never inspects or mutates the signed envelope and ignores any Access-asserted identity headers.

### Out of Scope
- Request-signing credential issuance logic itself — the auto-issue and OAuth2 client-credentials-style flows (Phase 6). This phase stands up the Issuer as infrastructure; Phase 6 implements what it issues beyond transport credentials.
- Changes to Skylar Core itself.

## 3. Deliverables

1. Updated OCI VM configuration with locked-down Tailscale ACLs.
2. Working Cloudflare Tunnel + Access configuration.
3. Issuer service capable of issuing short-lived Tailscale auth keys and Cloudflare service tokens, ready to be extended by Phase 6 for request-signing credentials.
4. Key-isolation implementation record confirming the decided posture (same-host, separate secret storage) is in place, plus the standing note that separate-host/HSM/KMS is tracked as future work.
5. Operational run-book for starting / stopping / rotating the tunnel and Issuer.

## 4. Work Items

1. Capture current Tailscale node status and ACL tags on the OCI VM.
2. Tighten ACLs so the relay can dial only the Skylar mesh address and port required for forwarding.
3. Install `cloudflared`, create a tunnel, and point it at the local forwarder listener.
4. Configure Cloudflare Access with a service-token policy suitable for ephemeral callers.
5. Implement or deploy the Issuer as a small standalone process on the OCI VM; implement separate secret storage for its signing material versus the relay's transport identity.
6. Confirm that any Access identity headers are stripped or ignored before the envelope is placed on the mesh.
7. Record the key-isolation posture (same-host, separate storage, residual risk accepted) as implemented, referencing the architecture doc's §5 decision.
8. Perform a basic connectivity test: an authorized service token can open the tunnel and deliver an opaque payload that appears on the mesh.

## 5. Validation Criteria

| ID | Criterion | Evidence Required |
|----|-----------|-------------------|
| V5.1 | OCI VM is a Tailscale mesh member with restricted ACLs | `tailscale status` + ACL export |
| V5.2 | Cloudflare Tunnel is healthy and reachable only through Access | Cloudflare dashboard + test request |
| V5.3 | Service token grants access; missing or invalid token is rejected | Negative and positive tests |
| V5.4 | Envelope reaches the mesh unmodified | Packet or log inspection |
| V5.5 | Issuer can mint a usable Tailscale auth key and a Cloudflare service token | Issuance test |
| V5.6 | Issuer signing-key storage is verifiably separate from the Relay Forwarder's transport identity storage on the shared host | Configuration / keystore review |

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Overly permissive ACLs | Lateral movement risk | Explicit least-privilege review |
| Same-host compromise exposes both Issuer and Relay Forwarder secrets despite separate storage | Elevated blast radius (accepted residual risk for this phase) | Separate-host / HSM / KMS remains a tracked future hardening item; not required to exit this phase |
| Cloudflare configuration drift | Availability or security issue | Version-control tunnel and Access configs where possible |

## 7. Exit Condition

Phase 5 is complete when an opaque signed envelope can be injected through Cloudflare Access + Tunnel, appear unmodified on the private mesh, the Issuer can produce transport credentials required by later end-to-end phases, and the decided key-isolation posture is confirmed implemented (not merely documented as a future choice).

---

# Phase 6 — Request-Signing Credential Bootstrap *(New in v1.1)*

**Phase ID:** 6
**Dependencies:** Phase 3 complete (authorization matrix / caller identity model exists); Phase 5 complete (Issuer infrastructure exists)
**Estimated duration:** 5–8 working days
**Validation gate:** Both caller classes can obtain a valid request-signing credential through their respective flows, and Skylar Core accepts envelopes signed with credentials issued this way

## 1. Objectives

Implement and validate the two request-signing credential issuance paths defined in the architecture doc (§5): persistent-caller auto-issue triggered by completed Tailscale enrollment, and the ephemeral-caller OAuth2 client-credentials-style exchange against the Issuer. This phase is a **hard gate** — neither Lane A nor Lane B end-to-end testing (Phases 7–8) may proceed using mocked or stubbed signing credentials once this phase is complete; both must use credentials issued by the real flows built here.

## 2. Scope

### In Scope
- Persistent-caller auto-issue: the Issuer mints a signing credential automatically the moment a known caller's device completes Tailscale enrollment, scoped per that caller's authorization matrix entry. This is a distinct, second issuance step triggered by (not implied by) successful enrollment — mesh reachability alone must never be sufficient to produce a valid envelope.
- Ephemeral-caller OAuth2 client-credentials-style flow: pre-registration of client entries (client ID/secret or equivalent) for known ephemeral caller classes; the Issuer validates the caller's Cloudflare Access identity together with its registered client entry before minting a short-lived, scope-limited signing credential.
- `caller_id` ↔ credential binding and key storage/rotation cadence per caller class (persistent: longer-lived with rotation; ephemeral: single-use or short TTL), per architecture §5.
- Test harness that requests, receives, and uses freshly issued credentials to sign a real envelope and submit it against Skylar Core's existing local test surface (from Phases 3–4), confirming acceptance.

### Out of Scope
- Network transport for the Lane A/B end-to-end paths themselves (Phases 7–8) — this phase proves issuance and local acceptance only.
- Break-glass revocation implementation (design specified in the architecture doc §11; implementation deferred post-MVP).
- Production-grade self-service provisioning tooling for ephemeral client entries beyond what's needed for test coverage — manual/documented provisioning is acceptable for this phase.

## 3. Deliverables

1. Persistent-caller auto-issue implementation, triggered by confirmed Tailscale enrollment completion.
2. Ephemeral-caller OAuth2 client-credentials-style exchange implementation in the Issuer.
3. Test suite demonstrating both flows produce a signing credential that Skylar Core's verification pipeline accepts.
4. Documented client-entry provisioning procedure for ephemeral callers (manual for this phase).
5. Negative-test confirmation that a caller holding only a transport credential cannot produce an envelope Skylar accepts.

## 4. Work Items

1. Extend the Issuer (Phase 5) with a persistent-caller auto-issue path triggered by Tailscale enrollment-completion events.
2. Implement the ephemeral-caller OAuth2 client-credentials-style token exchange, including pre-registered client-entry validation.
3. Bind issued signing credentials to `caller_id` entries consistent with the authorization matrix (Phase 3).
4. Implement rotation/TTL behaviour per caller class per architecture §5.
5. Build a test harness that exercises both issuance paths end to end and signs/submits envelopes to Skylar Core's existing local test surface.
6. Confirm the negative case: a transport credential alone never yields a valid signature.
7. Document the manual client-entry provisioning procedure for ephemeral callers.

## 5. Validation Criteria

| ID | Criterion | Evidence Required |
|----|-----------|-------------------|
| V6.1 | Persistent caller receives a signing credential automatically upon completed Tailscale enrollment | Issuance log + test |
| V6.2 | Ephemeral caller completes the OAuth2 client-credentials-style exchange and receives a scoped, short-lived signing credential | Issuance log + test |
| V6.3 | Envelopes signed with credentials from either flow are accepted by Skylar Core's verification pipeline | Test log |
| V6.4 | A caller holding only a transport credential cannot produce a Skylar-accepted signature | Negative test |
| V6.5 | Issued credential scope matches the caller's authorization matrix entry | Test log |
| V6.6 | Rotation/TTL behaviour matches architecture §5 (persistent: longer-lived + rotation; ephemeral: short TTL/single-use) | Code + test review |

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Auto-issue path fires on unintended or duplicate enrollment events | Credential over-issuance / sprawl | Bind auto-issue to the caller registry entry, not raw enrollment events |
| Ephemeral client-entry provisioning is manual and doesn't scale | Onboarding friction for new ephemeral callers | Acceptable for this phase; automate in a later hardening pass |
| Transport and signing credentials get confused in test tooling | False validation results | Keep the two credential types visibly distinct in all test scripts and logs |

## 7. Exit Condition

Phase 6 is complete when both issuance flows are implemented, validated, and demonstrated to gate correctly — no envelope is accepted without a credential from a real issuance flow. **Phases 7 and 8 may not begin until this phase's validation criteria are satisfied.**

---

# Phase 7 — Lane A (Private Mesh) End-to-End Validation

**Phase ID:** 7
**Dependencies:** Phases 2, 3, 4, 5, and 6 complete (or Phase 2 fallback decision accepted)
**Estimated duration:** 5–8 working days
**Validation gate:** Persistent caller can exercise authorized capabilities over the Tailscale mesh, using a real Phase 6 signing credential

## 1. Objectives

Demonstrate a complete Lane A request path:
Persistent caller → Tailscale mesh (via embedded or fallback node) → Skylar Core → authorized Capability Target.

All security checks (signature, authorization matrix, nonce, routing, platform IPC permissions) must be exercised under real network conditions, using credentials issued by Phase 6's real flows — not test-only mocked credentials.

## 2. Scope

### In Scope
- Provisioning of a persistent caller identity via the Phase 6 auto-issue flow (transport credential + request-signing credential).
- End-to-end request from an external device or process that is a member of the same Tailscale mesh.
- Verification that Skylar's embedded (or fallback) node correctly accepts the connection while still performing independent cryptographic authorization.
- Positive and negative test cases (valid capability, insufficient scope, replay, expired envelope).
- Confirmation that the Relay Forwarder path is not required for Lane A success.

### Out of Scope
- Lane B / Cloudflare path (Phase 8).
- Scriptable mailbox harness (Phase 9).

## 3. Deliverables

1. Documented test caller setup, referencing the Phase 6 auto-issue flow.
2. Automated or scripted Lane A test suite.
3. Evidence package (logs, audit records, target side-effects) showing successful and rejected requests.
4. Updated operational notes for mesh node lifecycle inside Skylar.

## 4. Work Items

1. Issue a short-lived Tailscale auth key and confirm the Phase 6 auto-issue flow mints the corresponding request-signing credential for the test caller.
2. From a second mesh member, construct and send signed envelopes targeting known capabilities.
3. Confirm Skylar receives, verifies, authorizes, and dispatches the request.
4. Exercise denial paths (wrong capability, replay, expiry).
5. Confirm audit records are written correctly.
6. Verify that a non-mesh or unauthorized mesh node cannot reach the Skylar listening surface in a useful way.
7. Capture timing and reliability observations under modest load.

## 5. Validation Criteria

| ID | Criterion | Evidence Required |
|----|-----------|-------------------|
| V7.1 | Authorized capability request from a mesh member succeeds and produces the expected target effect | End-to-end log + target observation |
| V7.2 | Request with valid signature but insufficient scope is denied by Skylar | Audit / decision log |
| V7.3 | Replay of a previously accepted nonce is rejected | Test case |
| V7.4 | Expired envelope is rejected | Test case |
| V7.5 | Skylar's mesh node remains userspace-only (or fallback behaviour matches the Phase 2 decision) | Runtime confirmation |
| V7.6 | Audit trail is complete for both allow and deny outcomes | Audit samples |

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Mesh connectivity flapping on mobile networks | Flaky tests | Prefer stable Wi-Fi or wired test environment for formal validation |
| Auth-key leakage during testing | Credential compromise | Use short TTLs and revoke after the test window |
| Subtle differences between embedded and fallback transport | Behavioural divergence | Explicitly test the chosen transport path |

## 7. Exit Condition

Phase 7 is complete when the Lane A test suite passes all validation criteria and the evidence package has been reviewed. Lane A is then considered operationally validated.

---

# Phase 8 — Lane B (Public Edge-Gated) End-to-End Validation

**Phase ID:** 8
**Dependencies:** Phases 5, 6, and 7 complete
**Estimated duration:** 5–8 working days
**Validation gate:** Ephemeral caller can exercise authorized capabilities via Cloudflare Access → Tunnel → Relay → mesh → Skylar, using a real Phase 6 signing credential

## 1. Objectives

Demonstrate a complete Lane B request path:
Ephemeral caller → Cloudflare Access (service token) → Cloudflare Tunnel → OCI Relay Forwarder → private Tailscale mesh → Skylar Core → authorized Capability Target.

Skylar must perform the identical cryptographic and policy checks that it performs for Lane A; the ingress path must never be treated as authorization. Request-signing credential bootstrapping is no longer an open item at this point — this phase exercises the real Phase 6 OAuth2 client-credentials-style flow, not a mocked substitute.

## 2. Scope

### In Scope
- Issuance of a short-lived Cloudflare Access service token, plus a request-signing credential obtained via the Phase 6 OAuth2 client-credentials-style flow.
- End-to-end request originating outside the private mesh.
- Confirmation that the Relay Forwarder performs only opaque forwarding.
- Positive and negative test cases equivalent to those exercised in Phase 7.
- Verification that Access-asserted identity headers, if present, are ignored by Skylar.

### Out of Scope
- Scriptable mailbox harness (Phase 9).

## 3. Deliverables

1. Documented ephemeral-caller test procedure, referencing the Phase 6 OAuth2 flow.
2. Automated or scripted Lane B test suite.
3. Evidence package demonstrating success and rejection paths.
4. Confirmation that compromise of the Relay Forwarder cannot enlarge caller privileges.

## 4. Work Items

1. Obtain a signing credential via the Phase 6 OAuth2 client-credentials-style exchange, plus a service token from Cloudflare Access.
2. From an external host, POST a correctly signed envelope through the Access-protected tunnel endpoint.
3. Confirm the envelope arrives unmodified at Skylar via the mesh.
4. Confirm Skylar verifies, authorizes, and dispatches exactly as in Lane A.
5. Exercise denial paths (invalid token, invalid signature, insufficient scope, replay, expiry).
6. Confirm that removing or altering Access headers has no effect on Skylar's decision.
7. Capture latency and reliability observations.

## 5. Validation Criteria

| ID | Criterion | Evidence Required |
|----|-----------|-------------------|
| V8.1 | Authorized capability request via Lane B succeeds and produces the expected target effect | End-to-end log + target observation |
| V8.2 | Missing or invalid Access token is rejected at the edge | Cloudflare / tunnel logs |
| V8.3 | Valid token + invalid signature is rejected by Skylar | Audit / decision log |
| V8.4 | Replay and expiry are rejected identically to Lane A | Test cases |
| V8.5 | Relay Forwarder does not alter the envelope or assert caller identity | Inspection of forwarded payload |
| V8.6 | Audit trail is complete and attributes the decision correctly | Audit samples |

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Cloudflare rate limits or regional latency | Test flakiness | Use a stable test region and modest request volume |
| Accidental exposure of the tunnel without Access | Security incident | Confirm Access is enforced before any public testing |
| Confusion between transport token and signing credential | Incorrect test conclusions | Keep the two credentials clearly separated in test scripts |

## 7. Exit Condition

Phase 8 is complete when the Lane B test suite passes all validation criteria. Both ingress lanes are then considered validated under the architecture's security contract, using real, not mocked, credential issuance throughout.

---

# Phase 9 — Scriptable Mailbox Endpoint App & Final Validation Suite

**Phase ID:** 9
**Dependencies:** Phases 1–8 complete
**Estimated duration:** 8–12 working days
**Validation gate:** Skylar Context Gateway successfully completes a full series of automated tests against the scriptable mailbox endpoint across both lanes

## 1. Objectives

Introduce a dedicated, scriptable Capability Target — the **Mailbox Endpoint App** — whose sole purpose is to serve as a deterministic, observable, and programmable test oracle. The mailbox must accept authorized capability invocations from Skylar, record them, optionally execute simple scripted responses, and expose a query interface so that an external test runner can assert outcomes.

At the conclusion of this phase the Skylar Context Gateway shall demonstrate, through an automated test suite, that it correctly handles a comprehensive matrix of scenarios on both Lane A and Lane B.

## 2. Scope

### In Scope
- Design and implementation of a lightweight Android app (or equivalent isolated process) that registers as a Capability Target.
- AIDL surface protected by the same platform permission model used by Starlight / SFM / xtools.
- Scriptable behaviour: the mailbox can be pre-loaded with expected request patterns and corresponding success / failure responses.
- Persistent or queryable store of received envelopes (or their hashes) for later assertion.
- Automated test runner that:
  - Issues transport and signing credentials via the real Phase 6 flows.
  - Sends a battery of signed envelopes via Lane A and via Lane B.
  - Queries the mailbox to confirm correct delivery, ordering, and content.
  - Verifies denial, replay, expiry, and isolation cases.
- Final evidence package suitable for release readiness review.

### Out of Scope
- Production use of the mailbox (it is a test artefact only).
- Resolution of remaining open architecture items beyond what is required for the test suite (see Post-Phase Notes).

## 3. Deliverables

1. Scriptable Mailbox Endpoint App (APK + source).
2. Mailbox capability names registered in the signed routing table.
3. Automated test suite covering the scenarios listed in §5.
4. Final validation report summarising results for both lanes.
5. Recommendations for any residual defects or operational improvements discovered during testing.

## 4. Work Items

1. Design the minimal set of mailbox capabilities (e.g., `mailbox.store`, `mailbox.query`, `mailbox.clear`, `mailbox.scripted-reply`).
2. Implement the Android app with a protected AIDL surface and an internal store.
3. Provide a simple scripting / configuration interface so tests can pre-arm expected behaviour.
4. Register the mailbox capabilities in Skylar's signed routing table.
5. Extend the test harness used in Phases 7 and 8 to drive the mailbox.
6. Implement the full test matrix (see validation criteria).
7. Execute the suite against both Lane A and Lane B.
8. Collect logs, audit records, and mailbox state into a single evidence package.
9. Produce the final validation report.

## 5. Validation Criteria

| ID | Criterion | Evidence Required |
|----|-----------|-------------------|
| V9.1 | Authorized `mailbox.store` request via Lane A is received and recorded by the mailbox | Mailbox query + audit log |
| V9.2 | Identical request via Lane B is likewise received and recorded | Mailbox query + audit log |
| V9.3 | Request with insufficient scope is denied by Skylar and never reaches the mailbox | Audit log + empty mailbox |
| V9.4 | Replay of a previously accepted nonce is rejected on both lanes | Audit log |
| V9.5 | Expired envelope is rejected on both lanes | Audit log |
| V9.6 | Scripted reply capability returns the pre-configured response | Test assertion |
| V9.7 | Force-stopping the mailbox affects only mailbox capabilities; other targets remain reachable | Isolation test |
| V9.8 | Non-Skylar process attempting to call the mailbox AIDL surface is rejected | Negative test |
| V9.9 | Complete automated suite passes with zero unexpected failures | Test runner report |
| V9.10 | Final validation report is reviewed and accepted | Sign-off |

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Mailbox becomes a privileged target that is accidentally left in production builds | Security / operational risk | Explicit "test-only" packaging and clear documentation |
| Test suite non-determinism under real network conditions | False failures | Prefer controlled network environments for formal runs; add retries with clear diagnostics |
| Incomplete coverage of edge cases | Undetected defects | Maintain a living matrix of scenarios and expand it as new edge cases appear |

## 7. Exit Condition

Phase 9 is complete when the automated test suite has executed successfully against both ingress lanes, the evidence package has been reviewed, and the final validation report has been accepted. At that point the Skylar Context Gateway implementation exercise is considered successfully concluded for the scope defined by this development plan.

## 8. Post-Phase Notes

Remaining open architecture items outside the scope of this implementation plan:
- **Issuer key isolation beyond the decision recorded in Phase 5** — same-host/separate-secret-storage is implemented and validated by Phase 5; moving to a separate host or HSM/KMS remains a future hardening item, not scheduled.
- **Audit tamper-evidence hardening** — beyond the append-only file implemented in Phase 3; retention/PII policy also remains open.
- **Break-glass revocation** — design specified (system-wide kill switch plus granular per-caller-class revocation); implementation deferred, not built by this plan.
- **HA / failover design** for Skylar Core and the shared nonce cache.
- **Concrete quota / rate-limit values** per caller class.

Note: request-signing credential issuance bootstrapping, listed as open in v1.0 of this plan, is **no longer on this list** — it is implemented and validated by Phase 6. These remaining items should be tracked as follow-on work before any claim of full production readiness.
