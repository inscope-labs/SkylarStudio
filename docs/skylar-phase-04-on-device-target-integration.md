# Phase 4 — On-Device Target Integration (AIDL / Local IPC)

> ## ⚠️ STATUS CORRECTION (2026-09-12)
> **This document's original "Completed & Validated" status and the
> "PASSED" verdicts in the validation matrix below are INCORRECT.**
> The Starlight and SFM services described here were implemented as
> in-process mocks running inside Skylar's own APK/process/UID — not
> the real, separate `inscope-labs/Starlight` and `inscope-labs/abx-sfm-1`
> apps Phase 4 requires. No validation criterion that depends on
> genuine cross-process/cross-UID isolation (V4.1, V4.2, V4.4, V4.5)
> was actually exercised against anything other than the mock. See:
> - `docs/skylar-context-gateway-architecture-addenda.md`, entry dated
>   2026-09-12, "Phase 4 AIDL services were in-process mocks..."
> - `docs/skylar-phase-04-real-integration-requirements.md` for what
>   real completion actually requires.
>
> The technical content below (AIDL contracts, permission model,
> onboarding pattern) remains accurate as a **design description** and
> is unedited — it just hasn't been validated against real separate
> apps yet. The classes referenced below
> (`StarlightTargetService`/`SfmTargetService`) have since been renamed
> `MockStarlightTargetService`/`MockSfmTargetService` and relocated to
> `ipc/target/mock/` to make their nature unmistakable — this document's
> code references to the old names are left as historical record of
> what was built, not as current file paths.

**Document:** `docs/skylar-phase-04-on-device-target-integration.md`  
**Phase:** Phase 4 — On-Device Target Integration  
**Date:** 2026-09-12  
**Status:** ~~Completed & Validated~~ **Design drafted; NOT validated — see correction banner above**  

---

## 1. Overview & Objectives

Phase 4 connects Skylar Core to real co-resident Capability Targets (**Starlight**, **SFM**, and **xtools**) using platform-enforced local IPC (AIDL / Binder).

Per Architecture Specification §7 & §9:
- **No Network Sockets:** Co-resident targets are reached strictly via local IPC.
- **Platform Access Control:** Target IPC endpoints enforce platform-level access control (signature permissions and Binder caller-UID verification). Any caller other than Skylar Core is rejected at the Binder boundary with `SecurityException`.
- **Governed User-Consent Gate:** Skylar's authorization is an ingress check, not a user-consent bypass. Starlight's Request Inbox consent gate remains mandatory.
- **Scoped Fail-Closed Isolation:** Failure, crash, or force-stopping of one target (e.g., Starlight) affects only capabilities routed to that target; other targets (SFM, xtools) remain completely functional and reachable.

---

## 2. Protected AIDL Contracts

Three AIDL interface definitions specify the contract between Skylar Core and on-device capability targets in `app/src/main/aidl/com/inscopelabs/abx/skylar/ipc/aidl/`:

### 2.1 `IStarlightService.aidl`
```aidl
package com.inscopelabs.abx.skylar.ipc.aidl;

interface IStarlightService {
    String executeCapability(String capability, String paramsJson);
    boolean isAvailable();
    boolean requiresUserConsent(String capability);
    boolean approveWorkflow(String workflowId);
}
```

### 2.2 `ISfmService.aidl`
```aidl
package com.inscopelabs.abx.skylar.ipc.aidl;

interface ISfmService {
    String executeCapability(String capability, String paramsJson);
    boolean isAvailable();
    long getStorageQuotaBytes(String callerNamespace);
}
```

### 2.3 `ICapabilityTarget.aidl`
Generic base contract for any new capability targets:
```aidl
package com.inscopelabs.abx.skylar.ipc.aidl;

interface ICapabilityTarget {
    String executeCapability(String capability, String paramsJson);
    boolean isAvailable();
    boolean requiresUserConsent(String capability);
}
```

---

## 3. Platform Permission & Access Enforcement Model

### 3.1 Permission Declaration (`AndroidManifest.xml`)
A custom signature-level permission guarantees that only applications signed with the same developer signing key as Skylar Core can bind to the target services:

```xml
<permission
    android:name="com.inscopelabs.abx.skylar.permission.DISPATCH_CAPABILITY"
    android:protectionLevel="signature" />

<uses-permission android:name="com.inscopelabs.abx.skylar.permission.DISPATCH_CAPABILITY" />
```

Target services are exported to allow cross-UID local IPC on device, guarded by the signature permission:

```xml
<service
    android:name=".ipc.target.StarlightTargetService"
    android:exported="true"
    android:permission="com.inscopelabs.abx.skylar.permission.DISPATCH_CAPABILITY">
    <intent-filter>
        <action android:name="com.inscopelabs.abx.skylar.action.BIND_STARLIGHT" />
    </intent-filter>
</service>
```

### 3.2 Runtime Binder UID Verification (`TargetAccessEnforcer`)
In addition to Android package manager manifest checks, target services invoke `TargetAccessEnforcer.enforceSkylarCaller(context)` inside every AIDL transaction:

1. Obtains caller identity via `Binder.getCallingUid()`.
2. Validates against `Process.myUid()` (same process/shared UID).
3. Verifies `packageManager.checkPermission(DISPATCH_PERMISSION, ...)` equals `PERMISSION_GRANTED`.
4. Verifies `packageManager.checkSignatures(callingUid, myUid)` equals `SIGNATURE_MATCH`.
5. If none match, immediately throws `SecurityException` (failing closed).

### 3.3 Target Onboarding Pattern (How Future Targets Adopt the Model)
To register a new Capability Target with Skylar:
1. Include `DISPATCH_PERMISSION` in the target app's manifest: `<uses-permission android:name="com.inscopelabs.abx.skylar.permission.DISPATCH_CAPABILITY" />`.
2. Implement `ICapabilityTarget.Stub` or dedicated AIDL interface.
3. Guard all AIDL methods with `TargetAccessEnforcer.enforceSkylarCaller(context)`.
4. Register the capability routing entry in Skylar's signed `RoutingTable`.

---

## 4. Starlight Governed User-Consent Gate

Per Architecture §3.3:
> *"Skylar authorizing a request must still not bypass user approval — Skylar's authorization and Starlight's user-consent gate are two separate checks, both required."*

In `StarlightTargetService`:
- Autonomous capabilities (e.g. `context.query`, `starlight.status`) execute directly when Skylar authorization passes.
- Governed capabilities (`starlight.workflow.start`, `ui.action.execute`, `device.control`) evaluate `requiresUserConsent(capability) == true`.
- An incoming request creates a `PendingWorkflow` entry in the `RequestInbox` and returns:
  ```json
  {
    "status": "PENDING_USER_CONSENT",
    "workflow_id": "wf-1726156800000",
    "capability": "starlight.workflow.start",
    "message": "Queued in Starlight Request Inbox awaiting user confirmation"
  }
  ```
- Only after user approval is recorded via `approveWorkflow(workflowId)` will execution proceed.

---

## 5. SFM (Storage Vault) Target

`SfmTargetService` implements `ISfmService.Stub`:
- Enforces `TargetAccessEnforcer` on all storage methods.
- Dispatches operations: `storage.read`, `storage.write`, `storage.list`, `vault.execute`.
- Manages storage quotas per caller namespace.

---

## 6. xtools In-Process Plugin Bridge & Two-Tier Trust Model

`XtoolsBridge` provides the execution adapter for scriptable and plugin capabilities:
- First tier: Caller must be Skylar Core (enforced via `TargetAccessEnforcer`).
- Second tier: Evaluates plugin trust tier (`PIPELINE_SIGNED`, `VERIFIED`, `COMMUNITY_UNTRUSTED`). If a high-privilege capability (e.g. `system.execute`) is invoked with an untrusted or insufficiently signed plugin, xtools rejects it internally with `PLUGIN_TRUST_TIER_INSUFFICIENT`.

---

## 7. Fault Isolation & Scoped Fail-Closed Behavior

Per Architecture §9:
- If Starlight crashes or is force-stopped:
  - `StarlightClient` catches `DeadObjectException` and marks Starlight as disconnected.
  - Skylar Core returns `TARGET_CRASHED` or `TARGET_UNAVAILABLE` specifically for Starlight capabilities.
  - SFM and xtools remain completely reachable and operational.
  - An outage in one target never compromises or degrades unrelated capabilities.

---

## 8. Validation Results Matrix

| ID | Criterion | Verification Mechanism | Status |
|---|---|---|---|
| **V4.1** | Authorized request reaches correct target and produces observable result | Dispatched via `StarlightClient`/`SfmClient`/`XtoolsBridge` to **in-process mock** services only — no real target app, no device-level observation | **NOT VALIDATED** — needs real separate target apps + device test |
| **V4.2** | Request from non-Skylar UID is rejected by target's AIDL surface | `TargetAccessEnforcer`'s own comparison logic tested via a manually-set test override (`setAllowedUidForTesting`); no genuinely different UID/app was ever the caller | **NOT VALIDATED** — needs a real second installed app |
| **V4.3** | Unauthorized capability denied by Skylar before dispatch | Tests Skylar Core's own authorization matrix, independent of whether the target is real or mock | **PASSED** — this criterion doesn't depend on real cross-app IPC |
| **V4.4** | Force-stopping Starlight leaves SFM and xtools functional | Simulated by injecting a `DeadObjectException`-throwing stub in the SAME process — not a real force-stop of a separate app | **NOT VALIDATED** — needs a real separate Starlight process to force-stop |
| **V4.5** | Starlight's *existing* user-consent gate is still enforced | Tested against a reimplemented mock consent gate, not Starlight's real, already-existing one | **NOT VALIDATED** — needs the real Starlight app's real gate |
| **V4.6** | Audit log records final decision for both allow and deny cases | Tests Skylar Core's own `AuditLogger`, independent of whether the target is real or mock | **PASSED** — this criterion doesn't depend on real cross-app IPC |
