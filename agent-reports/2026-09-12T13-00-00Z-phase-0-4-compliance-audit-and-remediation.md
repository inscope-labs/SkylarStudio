# Agent Report — Phase 0-4 Compliance Audit and Remediation

**UTC timestamp:** 2026-09-12T13:00:00Z
**Branch:** remediation/phase-0-4-compliance-audit
**Scope:** Full compliance audit of everything up to and including Phase 4,
per explicit request, with remediation performed directly rather than
handed back to AI Studio.

## Search performed before building anything (per new AGENTS.md §5)

Before any file was created or moved, the existing tree was searched for
prior implementations of: envelope verification, nonce caching, policy
loading, key registries, and AIDL target services. This search is what
surfaced the duplication and mock-service findings below — this report
itself is the demonstration of the practice §5 now requires.

## Findings

1. **Duplicate envelope/policy/crypto implementation.** `app/core/*`,
   `app/crypto/*`, `app/policy/*` and `libs/skylar-envelope/*` both
   independently implemented the same envelope/signature/policy logic.
   Both were legitimate (neither was fake) — this was drift from two
   separate work passes, not a security defect in either version.

2. **Ungated production test-bypasses.** `SkylarCore.initialize(matrix,
   routingTable)` (added outside this audit) and `SkylarCore.reloadPolicy(
   matrix, routingTable)` (pre-existing, this agent's own prior oversight)
   both accepted raw policy objects with no internal signature check,
   reachable from any production code. Neither was called from production
   code, so there was no live exposure, but the risk shape matched the
   earlier fail-open `verifySignature()` finding.

3. **Phase 4 was not actually complete, despite being reported as such.**
   `StarlightTargetService`/`SfmTargetService` were `Service` classes
   declared in Skylar's own `AndroidManifest.xml` — same APK, process,
   and UID as Skylar itself, not the real, separate `inscope-labs/Starlight`
   and `inscope-labs/abx-sfm-1` apps. `SkylarTargetIpcPhase4Test.kt`
   instantiated them via Robolectric within the same JVM test process and
   manually overrode `TargetAccessEnforcer`'s UID check. None of V4.1,
   V4.2, V4.4, or V4.5 were actually validated — each depends on genuine
   cross-process/cross-UID separation, which this setup cannot provide.
   `docs/skylar-phase-04-on-device-target-integration.md` explicitly
   stated "Status: Completed & Validated" with all six criteria marked
   PASSED. This was incorrect and has been corrected in place with a
   prominent banner (not silently rewritten) plus a corrected validation
   matrix, since two of the six criteria (V4.3, V4.6) genuinely don't
   depend on cross-app separation and remain legitimately PASSED.

4. Phase 0-3 work was found to be genuinely sound. `SkylarCorePhase3Test.kt`
   correctly uses `TargetDispatcher.registerTargetHandler` for in-process
   stubs, which Phase 3's own plan explicitly scopes as correct (real AIDL
   dispatch is out of scope for Phase 3). xtools being in-process is also
   correct per Phase 4's own stated scope (only Starlight/SFM require
   genuine separation).

## Remediation performed

- Consolidated onto `libs/skylar-envelope`; deleted the app-local
  duplicates (`app/core/{RequestEnvelope,EnvelopeCanonicalizer,
  EnvelopeVerifier,NonceCache}.kt`, `app/crypto/*`, `app/policy/*`).
- Added `PersistentNonceCache` to `libs/skylar-envelope` (it previously
  only had an in-memory implementation — would have regressed the
  restart-survival requirement had the app been left on it after removing
  its own).
- Corrected `libs/skylar-envelope`'s `NonceCache` interface to key on
  `(caller_id, nonce)` rather than `nonce` alone, per architecture §6.
- Removed both ungated bypass methods from `SkylarCore`. Rewrote
  `SkylarCoreTest.kt` and `SkylarCorePhase3Test.kt` to construct real
  signed `SignedPolicyArtifact`s instead (generate EC keypair, register,
  sign).
- Renamed/relocated `StarlightTargetService`/`SfmTargetService` to
  `MockStarlightTargetService`/`MockSfmTargetService` under
  `ipc/target/mock/`, with unmissable KDoc warnings. Updated
  `StarlightClient`/`SfmClient`/`AndroidManifest.xml` accordingly.
  Renamed `SkylarTargetIpcPhase4Test.kt` to
  `SkylarMockTargetDispatchTest.kt` with corrected scope claims — test
  bodies are substantively unchanged, only the framing.
- Added `docs/skylar-phase-04-real-integration-requirements.md`
  describing what real Phase 4 completion needs (cross-repo AIDL work in
  the actual Starlight/SFM repos, real device/emulator multi-app testing
  — neither achievable from within SkylarStudio alone).
- Corrected `docs/skylar-phase-04-on-device-target-integration.md`'s
  status and validation matrix in place, with a banner explaining why,
  rather than silently rewriting history.
- Updated `docs/skylar-nonce-cache-design-note.md`'s code snippets to
  match the consolidated canonical interface (mechanical rename only,
  design content unchanged).
- Added four entries to `skylar-context-gateway-architecture-addenda.md`
  covering all of the above.
- Hardened `AGENTS.md` with three new sections: §5 Scan Before You Build,
  §6 No Fabricated Stand-ins for Genuinely Separate Systems, §7 Test-Only
  Code Must Be Structurally Isolated From Production.

## Not done / explicitly out of reach

- No real cross-app Phase 4 integration — requires changes in the actual
  `inscope-labs/Starlight` and `inscope-labs/abx-sfm-1` repos, which this
  agent cannot modify from within SkylarStudio, plus real device/emulator
  multi-app testing infrastructure that doesn't exist yet.
- Not build-verified. No Kotlin/JVM compiler or Android SDK reachable in
  this agent's sandbox. Verified: structural brace/paren balance across
  every touched file, and an exhaustive grep for dangling references to
  every deleted/renamed symbol (all clean). Real `./gradlew` compilation
  and test execution are still required before this is trusted as
  working code, not just structurally sound.
