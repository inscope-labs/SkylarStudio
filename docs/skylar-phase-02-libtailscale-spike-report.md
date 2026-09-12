# Phase 2 — Embedded `libtailscale` Critical-Path Spike: Report

**Status:** DRAFT — scaffold only, no device/mesh testing has been run yet.
**Phase ID:** 2 (see `skylar-context-gateway-phased-development-plan.md`)

This report is a template. Every section below is a placeholder for
results that can only be produced by running the spike on a physical
device against a real Tailscale account — none of it should be treated
as evidence until filled in and the checkboxes are honestly ticked.

## 1. Binding Method

- [ ] Binding path chosen: `gomobile bind` / direct CGO / other (state which)
- [ ] Rationale for choice:
- [ ] Known limitations of the chosen path:

## 2. Build Integration

- [ ] `gomobile bind` artifact produced and vendored (location: `app/build.gradle.kts` TODO)
- [ ] `TsnetMeshNode` TODOs resolved against the artifact's actual API
- [ ] Project builds (`./gradlew :app:assembleDebug`) with the artifact present

## 3. Mesh Join

- [ ] Short-lived Tailscale auth key issued for this test
- [ ] Node observed joining the tailnet (evidence: `tailscale status` output or coordination-server view — attach/paste here)
- [ ] `tailscaleIp` populated in `MeshNodeState.Running` matches the observed node

## 4. VPN-Privilege Check (V2.2)

- [ ] `AndroidManifest.xml` inspected — confirm no `<service>` with `android.permission.BIND_VPN_SERVICE` or `android.net.VpnService` intent-filter
- [ ] Runtime check: Android's system VPN indicator does NOT appear while the node is running
- [ ] Evidence attached (screenshot or `dumpsys` excerpt)

## 5. Lifecycle Stress Test (V2.3, Phase 2 §4 work item 7)

Run on a physical device only — no emulator, per this project's standing rule.

| Scenario | Expected | Observed | Pass/Fail |
|---|---|---|---|
| Start → immediate stop | Clean stop, no crash | | |
| Start → app process killed → relaunch | Node recovers or cleanly re-starts, no orphaned session | | |
| Stop called while already stopped | No-op, no crash | | |
| Start called twice in a row | Second call handled sanely (reject or idempotent — decide and document) | | |
| Repeated start/stop x10 | No resource leak, no crash | | |
| Airplane mode toggled mid-session | Node reports `Error` or reconnects; no silent hang | | |

## 6. Key-Material Separation (Architecture doc §5)

- [ ] Confirm the transport credential (mesh node's key) is stored separately from any future envelope-verification key
- [ ] Document storage location and rotation approach (Phase 2 §4 work item 6)

## 7. Go / No-Go Recommendation

- [ ] Verdict:
- [ ] If "no-go" or "conditional go": see fallback design note (`skylar-phase-02-fallback-design-note.md`)

## 8. Observed Characteristics

- Memory:
- Battery:
- Startup latency (auth key → `Running` state):

---

*This report satisfies phased-development-plan Phase 2 §3 deliverable 2
once filled in. It does not, on its own, satisfy any validation
criterion (§5) — those require the underlying device/mesh evidence
referenced above.*
