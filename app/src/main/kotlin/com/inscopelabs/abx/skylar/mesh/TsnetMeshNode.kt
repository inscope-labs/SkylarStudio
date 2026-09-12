package com.inscopelabs.abx.skylar.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [MeshNode] implementation intended to be backed by the official
 * Tailscale `tsnet` engine, compiled to an Android-consumable artifact
 * via `gomobile bind` (phased-development-plan Phase 2 §2).
 *
 * ================================ STATUS ================================
 * SPIKE SCAFFOLD ONLY — NOT YET FUNCTIONAL.
 *
 * No `gomobile bind` output exists in this repo yet. Producing one
 * requires a Go toolchain + `gomobile`, evaluating the binding path
 * (Phase 2 §4 work item 1), and is explicitly the highest-uncertainty
 * part of this phase (plan's own estimate: "7-12 working days, high
 * uncertainty"). That step needs to run in an environment with the
 * actual Go/gomobile/Android NDK toolchain and is not something this
 * scaffolding pass can execute or fake convincingly.
 *
 * This class exists only to define the intended Kotlin-side surface and
 * mark exactly where the bound library's real calls need to be
 * substituted in, once produced. Every `start`/`stop` call currently
 * resolves to [MeshNodeState.Error] rather than silently pretending to
 * succeed — a stub that reported [MeshNodeState.Running] without an
 * actual tailnet session would be a false positive against validation
 * criterion V2.1, which requires confirmation via `tailscale status` or
 * the coordination server, not just an in-app state flag.
 *
 * Do not treat this class as satisfying any Phase 2 validation
 * criterion (V2.1-V2.6) until the TODOs below are resolved and verified
 * on a physical device, per this project's standing no-emulator rule.
 * ==========================================================================
 */
class TsnetMeshNode : MeshNode {

    private val _state = MutableStateFlow<MeshNodeState>(MeshNodeState.Stopped)
    override val state: StateFlow<MeshNodeState> = _state.asStateFlow()

    override suspend fun start(authKey: String) {
        require(authKey.isNotBlank()) { "authKey must not be blank" }
        _state.value = MeshNodeState.Starting

        // TODO(phase-2): replace with the actual bound tsnet call once the
        // gomobile-bind artifact is vendored (see app/build.gradle.kts).
        // The binding's exact API shape is unknown until the spike
        // evaluates the binding path (Phase 2 §4 work item 1) — this
        // scaffold intentionally does not guess at method names. Expected
        // shape, per the upstream tsnet Go API, is roughly:
        //
        //     val server = Tsnet.newServer()
        //     server.hostname = "skylar-core"
        //     server.authKey = authKey
        //     val status = server.up(context)   // joins the tailnet
        //
        // On success:
        //     _state.value = MeshNodeState.Running(tailscaleIp = status.tailscaleIPs.firstOrNull())
        // On failure:
        //     _state.value = MeshNodeState.Error(reason = <thrown/returned error>)

        _state.value = MeshNodeState.Error(
            reason = "tsnet binding not yet integrated — spike scaffold only, see class doc"
        )
    }

    override suspend fun stop() {
        if (_state.value is MeshNodeState.Stopped) return

        // TODO(phase-2): call the bound engine's shutdown/close once it
        // exists, then confirm the tailnet session is actually released
        // (not just that this method returned) before setting Stopped.

        _state.value = MeshNodeState.Stopped
    }

    override fun isRunning(): Boolean = state.value is MeshNodeState.Running
}
