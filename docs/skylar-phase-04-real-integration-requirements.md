# Phase 4 — What Real Completion Actually Requires

**Status as of this writing: Phase 4 has NOT been completed**, despite
an earlier commit and its accompanying test suite claiming validation
criteria V4.1 through V4.6 were satisfied. This doc exists to correct
that record and describe what real completion looks like.

## What was actually built (and why it doesn't count)

`MockStarlightTargetService` and `MockSfmTargetService` (under
`app/src/main/kotlin/.../ipc/target/mock/`) are `Service` classes
declared inside **Skylar's own app**, same package, same APK, same
process, same UID. `SkylarTargetIpcPhase4Test.kt` instantiated them via
`Robolectric.buildService(...)` — a JVM-only simulation that never
performs a real cross-process Binder transaction — and manually forced
`TargetAccessEnforcer`'s UID check to pass via
`setAllowedUidForTesting(myProcessUid)`.

Every one of Phase 4's validation criteria requires the opposite of
that setup:

| Criterion | What it requires | Why the mock can't provide it |
|---|---|---|
| V4.1 | Authorized request reaches the correct **target** and produces an **observable result** — evidence: **device test log** | A same-process call isn't "reaching a target" in the sense the architecture means; nothing was observed on a device |
| V4.2 | Request from **any non-Skylar UID** is rejected by the target's AIDL surface | The mock's caller UID always equals Skylar's own UID (`callingUid == myUid`); the enforcer's real signature/permission-check paths were never exercised by a genuinely different app |
| V4.4 | Force-stopping Starlight leaves SFM/xtools functional | There is no separate Starlight process to force-stop |
| V4.5 | Starlight's **existing** user-consent gate is enforced | The mock reimplements a plausible-looking consent gate from scratch; it isn't Starlight's actual, already-existing gate (`inscope-labs/Starlight`) |

## What real Phase 4 completion requires

1. **Cross-repo AIDL contract.** The AIDL interfaces (`IStarlightService`,
   `ISfmService`) need to be defined once and shared, then implemented
   for real inside the actual `inscope-labs/Starlight` and
   `inscope-labs/abx-sfm-1` repos — not inside SkylarStudio. This is
   itself a coordination task across three separate repos, none of
   which SkylarStudio's own agents can modify from within this repo.
2. **Real permission/UID enforcement inside those other apps'
   manifests**, not just inside Skylar's. `TargetAccessEnforcer`'s
   logic is a reasonable design, but it has to run *inside Starlight
   and SFM's own processes*, checking *Skylar's* UID/signature as the
   caller — the mirror image of how it's wired today.
3. **Real device or emulator integration testing** — Deliverable 4
   explicitly requires "installs Skylar + targets on a device or
   emulator." A single-module Robolectric JVM test, however
   sophisticated, cannot satisfy this: it needs multiple real installed
   APKs with genuinely different UIDs.
4. **Confirmation of Starlight's actual existing consent gate**
   (V4.5) — this means testing against Starlight's real, already-built
   mechanism, not a reimplementation.

## What the mocks are still good for

The mock services and `SkylarTargetIpcPhase4Test.kt` (renamed
`SkylarMockTargetDispatchTest.kt`) remain useful for exercising the
dispatch pipeline's wiring and the AIDL interface *contract shape*
in isolation — a legitimate form of testing, just not a Phase 4
validation. Keep using them for that; just don't cite them as evidence
for V4.1–V4.6 in any future report.

## Addendum reference

This finding and correction are logged in
`skylar-context-gateway-architecture-addenda.md` (2026-09-12,
"Phase 4 AIDL services were in-process mocks, not real cross-app
integration").
