# Skylar Context Gateway — Phase 0.5 Prototype Scaffold

**Application ID:** `com.inscopelabs.abx.skylar`  
**Package:** `com.inscopelabs.abx.skylar`  
**Architecture:** Single Policy Enforcement Point (verify → authorize → route → audit)

This repository contains the canonical Android implementation of the **Skylar Context Gateway** (`abx-server-1`), responsible for authenticating, authorizing, routing, and auditing capability requests to on-device execution targets (Starlight, SFM, xtools).

---

## 1. Prototype Components

The Phase 0.5 scaffold incorporates the core architectural layers:

```
app/src/main/kotlin/com/inscopelabs/abx/skylar/
├── SkylarApplication.kt         # Android application entry point & subsystem coordinator
├── MainActivity.kt              # Diagnostic dashboard and runtime shell
├── common/
│   └── Result.kt                # Functional result model (Success / Error)
├── config/
│   └── SkylarConfig.kt          # Timing constants, clock skew, and replay cache configuration
├── core/
│   ├── SkylarCore.kt            # Central verify → authorize → route → audit pipeline
│   ├── EnvelopeVerifier.kt      # Temporal, canonical hash, and signature validation
│   └── NonceCache.kt            # Persistent replay protection surviving process restarts
├── policy/
│   ├── AuthorizationMatrix.kt   # caller_id → {capability: scope} default-deny evaluation
│   ├── RoutingTable.kt          # capability → target routing resolution
│   └── PolicyLoader.kt          # Bundled signed policy artifact loader
├── ipc/
│   ├── TargetDispatcher.kt      # Scoped fail-closed execution dispatcher
│   ├── StarlightClient.kt       # Accessibility execution client adapter
│   ├── SfmClient.kt             # Storage vault execution client adapter
│   └── XtoolsBridge.kt          # System diagnostics / tools bridge adapter
├── mesh/
│   ├── MeshNodeManager.kt       # Embedded userspace mesh node lifecycle (Lane A)
│   └── TransportCredential.kt   # Transport reachability credential (non-authorizing)
├── audit/
│   ├── AuditRecord.kt           # Immutable audit record definition
│   └── AuditLogger.kt           # Append-only durable audit sink
└── diagnostics/                 # Crash handling and telemetry subsystem
```

---

## 2. Core Security Invariants

1. **Security Contract First**: Transport is never authorization. Holding a mesh credential grants network reachability only, never execution permission.
2. **Default-Deny**: Unsigned policy, unknown capabilities, unmapped routes, and missing caller scopes are rejected.
3. **Replay & Expiry Protection**: Nonce cache keyed by `caller_id + nonce` is persisted to disk and checked atomically before authorization.
4. **Scoped Fail-Closed**: Failure or unavailability of one target (e.g. Starlight) blocks only requests to that target without affecting other targets (e.g. SFM or xtools).
5. **Durable Audit**: Every decision (ALLOW or DENY) is logged to disk with envelope hash, timestamp, caller, capability, and latency.

---

## 3. Build & Verification

- **Assemble Debug APK**: `gradle assembleDebug`
- **Unit Tests**: `gradle :app:testDebugUnitTest`
