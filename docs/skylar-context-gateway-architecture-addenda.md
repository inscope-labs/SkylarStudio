# Skylar Context Gateway — Architecture Addenda

Append-only log of approved changes to the standing core foundation
documents (architecture, infrastructure, phased-development-plan).
The core documents are never edited in place for a change originating
mid-phase; the change is recorded here first, dated and attributed,
then folded into the core doc's next formal version bump.

## Format per entry

### [YYYY-MM-DD] <short title>
- **Phase:** <phase this arose from>
- **Change:** <what's being added/altered>
- **Rationale:** <why>
- **Affects:** <which core doc(s) / section(s)>
- **Status:** proposed | approved | folded into core doc vN

---

### [2026-09-12] Signature algorithm selected: ECDSA/P-256/SHA-256

- **Phase:** 1 (Shared Envelope & Policy Library) — arose while writing a
  real `SignatureProvider` implementation to replace a fail-open
  prototype stub.
- **Change:** Concrete signature algorithm is ECDSA over the P-256
  curve with SHA-256 ("SHA256withECDSA" in the JCA), implemented via
  the standard `java.security` API with no new dependency.
- **Rationale:** Architecture doc §2 leaves the algorithm as "Ed25519 or
  the algorithm selected by the project." Ed25519 is only available via
  Android's default JCA providers starting API 33; this project's
  `minSdk` is 24 (`app/build.gradle.kts`). ECDSA/P-256/SHA-256 works via
  `java.security` back to Android's earliest API levels.
- **Affects:** `skylar-context-gateway-architecture.md` §2 (currently
  says "Ed25519 or the algorithm selected by the project" — should be
  updated to name ECDSA/P-256/SHA-256 concretely once reviewed).
- **Status:** proposed

---

### [2026-09-12] Consolidated duplicate envelope/policy implementation onto libs/skylar-envelope

- **Phase:** 1/3 — found during a Phase 0-4 compliance audit.
- **Change:** `app/core/{RequestEnvelope,EnvelopeCanonicalizer,EnvelopeVerifier,NonceCache}.kt`,
  `app/crypto/{SignatureProvider,KeyRegistry}.kt`, and `app/policy/*.kt`
  removed. `SkylarCore`/`SkylarApplication` now consume
  `libs/skylar-envelope` exclusively. Added `PersistentNonceCache` to
  `libs/skylar-envelope` (it previously only had an in-memory
  implementation, which would have regressed the restart-survival
  requirement below). Corrected `libs/skylar-envelope`'s `NonceCache`
  interface to key on `(caller_id, nonce)` rather than `nonce` alone.
- **Rationale:** two independent, legitimate (not fake) implementations
  of the same envelope/policy/crypto logic existed after two separate
  work passes each built their own. `libs/skylar-envelope` is the
  architecturally correct location per the canonical repo structure
  doc (a shared module other repos can consume); the app-local version
  predated it and was never migrated off. Keeping both was a live
  drift risk regardless of code quality.
- **Affects:** `skylar-context-gateway-canonical-repo-structure.md`
  (confirms `libs/skylar-envelope` as the single source going forward).
- **Status:** approved (this pass)

### [2026-09-12] Removed ungated test-bypass methods from SkylarCore

- **Phase:** 3 — found during the same audit.
- **Change:** Removed `SkylarCore.initialize(matrix: AuthorizationMatrix, routingTable: RoutingTable)`
  (added outside this audit, labeled "for internal testing" but living
  unguarded in production source) and `SkylarCore.reloadPolicy(matrix, routingTable)`
  (pre-existing from this class's original version, same unguarded shape,
  unused anywhere). Tests now construct real `SignedPolicyArtifact`s
  (generate an EC keypair, register it, sign a real payload) instead.
- **Rationale:** both methods accepted pre-built policy objects with no
  internal signature verification, reachable from any production code
  with an ordinary-looking call. Nothing called either method from
  production, so there was no live exposure — but the risk shape is
  identical to the fail-open `verifySignature()` stub found earlier in
  this project's history: a shortcut that looks safe until something,
  someday, calls it.
- **Affects:** none (implementation detail, no doc claims changed).
- **Status:** approved (this pass)

### [2026-09-12] Nonce-check ordering relative to signature verification

- **Phase:** 3.
- **Change:** Confirmed (not changed) that `SkylarCore.processEnvelope`
  verifies the envelope's signature BEFORE checking-and-marking the
  nonce as seen, not after — the reverse of the literal work-item
  ordering in `phased-development-plan.md` Phase 3 §4 ("expiry → nonce
  check → signature verify → authorization → route").
- **Rationale:** marking a nonce "seen" for an envelope whose signature
  hasn't been confirmed yet would let an attacker with no valid
  signature still consume a legitimate caller's future nonce value —
  a denial-of-service vector against that caller, since a nonce is
  meant to be single-use. Checking signature validity first means an
  unsigned/invalidly-signed request can never affect the nonce cache
  at all.
- **Affects:** `skylar-context-gateway-phased-development-plan.md`
  Phase 3 §4 work item 4 (ordering as literally written should be
  read as expiry/format checks before authorization, not as a strict
  nonce-before-signature requirement).
- **Status:** proposed

### [2026-09-12] Phase 4 AIDL services were in-process mocks, not real cross-app integration

- **Phase:** 4.
- **Change:** `StarlightTargetService`/`SfmTargetService` (declared in
  Skylar's own `AndroidManifest.xml`, same APK/process/UID as Skylar
  itself) have been renamed `MockStarlightTargetService`/
  `MockSfmTargetService`, relocated to `ipc/target/mock/`, and given
  explicit KDoc warnings. `SkylarTargetIpcPhase4Test.kt` (which tested
  them via Robolectric in-process instantiation with a manually
  overridden UID check) is renamed `SkylarMockTargetDispatchTest.kt`
  with corrected scope claims. A new doc,
  `skylar-phase-04-real-integration-requirements.md`, describes what
  real completion requires.
- **Rationale:** a prior commit and its test suite claimed validation
  criteria V4.1-V4.6 were satisfied. They were not: every one of those
  criteria requires a genuinely separate installed app with a
  genuinely different UID (per the canonical repo structure doc's own
  UID-isolation rationale for keeping Starlight/SFM as separate
  repos), and this implementation ran entirely in one process with the
  UID check manually forced to pass. xtools is unaffected — Phase 4's
  own scope explicitly permits xtools to be in-process.
- **Affects:** `skylar-context-gateway-phased-development-plan.md`
  Phase 4 — no criteria are currently satisfied; real completion needs
  cross-repo work in `inscope-labs/Starlight` and
  `inscope-labs/abx-sfm-1` plus real device/emulator multi-app testing,
  neither of which is achievable from within SkylarStudio alone.
- **Status:** approved (correction of a prior false-complete claim)
