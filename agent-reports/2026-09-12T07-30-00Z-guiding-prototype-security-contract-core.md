# Agent Report — Guiding Prototype: Security Contract Core (12 files)

**UTC timestamp:** 2026-09-12T07:30:00Z
**Branch:** guiding-prototype/security-contract-core
**Base:** SkylarStudio main @ ea00f4a

## Purpose

Per John's request: audit found the "Phase 0.5 scaffold" AI Studio
committed to SkylarStudio main (ea00f4a) contained fail-open security
logic (EnvelopeVerifier.verifySignature() accepted any 16+ char string;
PolicyLoader hardcoded a fake "sig_valid_proto_..." signature that the
same fake verifier would accept). This is a guiding-level, more complete
replacement for the 12 most critical files, intended to minimize drift
as the phased plan progresses — not a full Phase 1 implementation.

## Files replaced/added (12 total)

1. core/RequestEnvelope.kt (split out of EnvelopeVerifier.kt)
2. core/EnvelopeCanonicalizer.kt (new — real recursive canonicalization + workflow_hash)
3. crypto/SignatureProvider.kt (new — real ECDSA/P-256/SHA-256 + dependency-free Base64Codec)
4. crypto/KeyRegistry.kt (new — this concept didn't exist before; needed for verification against a registered public key)
5. core/EnvelopeVerifier.kt (rewritten — fail-closed, real signature check)
6. core/NonceCache.kt (rewritten — interface + commit()-based hardened persistence)
7. policy/SignedPolicyArtifact.kt (new — real signed-container verification)
8. policy/AuthorizationMatrix.kt (rewritten — signature concern moved to #7)
9. policy/RoutingTable.kt (rewritten — same treatment)
10. policy/PolicyLoader.kt (rewritten — real signature-gated loading, no fake baseline)
11. audit/AuditRecord.kt (added policyVersion field)
12. core/SkylarCore.kt (rewritten orchestrator — fails closed when no verified policy exists)

## Real decision made (needs review)

Signature algorithm: ECDSA/P-256/SHA-256, not Ed25519. Ed25519 needs API
33+; this project's minSdk is 24. Logged as an addenda entry
(docs/skylar-context-gateway-architecture-addenda.md, 2026-09-12,
status: proposed).

## Known breakage outside these 12 files (not fixed in this pass — flagged instead)

- `SkylarApplication.kt`: `SkylarCore(applicationContext)` no longer
  compiles — `SkylarCore` now requires a `KeyRegistry` argument. Needs
  an explicit (currently: test-only `InMemoryKeyRegistry`) instance
  supplied at the call site.
- `app/src/test/kotlin/.../SkylarCoreTest.kt`: `EnvelopeVerifier(SkylarConfig.DEFAULT)`
  and `verifier.computeWorkflowHash(...)` no longer compile — signature
  moved to `EnvelopeCanonicalizer.computeWorkflowHash(...)` (a static
  method) and `EnvelopeVerifier`'s constructor now requires a
  `KeyRegistry`. Its fake `"sig_valid_test_signature_123"` fixtures
  would also now correctly fail real verification — that test file
  needs real EC test keypairs, not just a compile fix.

## Not build-verified

No Kotlin compiler or Android SDK reachable in this agent's sandbox.
Verified: brace/paren structural balance across all 12 files; the
Base64Codec's exact bit-manipulation algorithm against RFC 4648 test
vectors via an equivalent standalone port (all passed). Real
`./gradlew` compilation still required before this is trusted.
