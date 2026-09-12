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
