package com.inscopelabs.abx.skylar.mesh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * [MeshNode] implementation backed by the official Tailscale `tsnet`
 * engine, compiled to an Android-consumable artifact via `gomobile bind`
 * (phased-development-plan Phase 2 §2), through a thin wrapper package
 * (`tools/tsnet-android-bind/tsnetbind/`) since `gobind` cannot bind
 * `tailscale.com/tsnet`'s own exported surface directly (its
 * `Server.Loopback()` returns four values; `gobind` allows at most one
 * non-error return — see `tsnetbind.go`'s doc comment).
 *
 * ================================ STATUS ================================
 * WIRED, BUT STILL UNVALIDATED ON A REAL DEVICE.
 *
 * What's actually been verified, mechanically, via CI (not guessed):
 *  - `gomobile bind` against the `tsnetbind` wrapper compiles into a real
 *    Android AAR (`.github/workflows/phase-2-libtailscale-bind.yml`).
 *  - The generated Java API's exact shape — `Server(String,String,String)`
 *    constructor, `start(): String throws Exception`,
 *    `stop(): Unit throws Exception` — was read directly from the actual
 *    `gobind`-generated source
 *    (`tools/tsnet-android-bind/generated-java-preview/Server.java`),
 *    captured by that same workflow, not assumed from documentation.
 *  - `:app:compileDebugKotlin` succeeds against this wiring.
 *
 * What has NOT been verified:
 *  - No physical device has run this. No real Tailscale auth key has been
 *    used. No real tailnet join has been observed via `tailscale status`
 *    or the coordination server — which is what validation criterion V2.1
 *    actually requires, not just that this class compiles and returns a
 *    plausible-looking state.
 *  - `stateDir` (tsnet's persistent-state directory) has no real value
 *    wired in from anywhere in the app yet — see the constructor's doc
 *    comment. Until something passes a real one in (e.g.
 *    `context.filesDir.absolutePath`), `start()` returns
 *    [MeshNodeState.Error] rather than pretending to succeed.
 *  - The spike report (`docs/skylar-phase-02-libtailscale-spike-report.md`)
 *    is still an unfilled template. This class compiling is not a
 *    substitute for that report's own checklist, and is not itself a
 *    go/no-go decision.
 *
 * Do not treat this class as satisfying any Phase 2 validation criterion
 * (V2.1-V2.6) until it has actually run on a physical device, per this
 * project's standing no-emulator rule, and the spike report reflects that.
 * ==========================================================================
 */
class TsnetMeshNode(
    /** Hostname this node presents on the tailnet. */
    private val hostname: String = "skylar-core",
    /**
     * Writable local directory for tsnet's persistent state.
     *
     * No default resolves this to a real path — there's no Android
     * [android.content.Context] available at this class's construction
     * site yet (see `SkylarCore.kt`'s `TsnetMeshNode()` default-parameter
     * instantiation and `LaneAEndToEndPhase7Test.kt`'s no-arg usage; both
     * predate this wiring and are left unbroken by keeping this
     * parameter optional). Left `null` until something in the app's
     * actual composition root passes a real value in
     * (e.g. `TsnetMeshNode(stateDir = context.filesDir.absolutePath)`) —
     * that wiring is a separate, explicit follow-up, not implied here.
     */
    private val stateDir: String? = null,
) : MeshNode {

    private val _state = MutableStateFlow<MeshNodeState>(MeshNodeState.Stopped)
    override val state: StateFlow<MeshNodeState> = _state.asStateFlow()

    private var server: tsnetbind.Server? = null

    override suspend fun start(authKey: String) {
        require(authKey.isNotBlank()) { "authKey must not be blank" }
        _state.value = MeshNodeState.Starting

        val dir = stateDir
        if (dir == null) {
            _state.value = MeshNodeState.Error(
                reason = "no stateDir configured for this TsnetMeshNode instance — " +
                    "construct it with a real writable directory (e.g. " +
                    "context.filesDir.absolutePath) before calling start(); " +
                    "see this class's stateDir doc comment"
            )
            return
        }

        try {
            // tsnetbind.Server's native start()/stop() block on real
            // network I/O (tailnet join, DERP negotiation, etc.) — must
            // not run on the calling coroutine's dispatcher if it's Main.
            val tailscaleIp = withContext(Dispatchers.IO) {
                val newServer = tsnetbind.Server(hostname, authKey, dir)
                server = newServer
                newServer.start()
            }
            _state.value = MeshNodeState.Running(tailscaleIp = tailscaleIp)
        } catch (e: Exception) {
            server = null
            _state.value = MeshNodeState.Error(reason = e.message ?: e.toString())
        }
    }

    override suspend fun stop() {
        if (_state.value is MeshNodeState.Stopped) return

        val current = server
        server = null
        if (current != null) {
            try {
                withContext(Dispatchers.IO) { current.stop() }
            } catch (e: Exception) {
                // A failed stop() still shouldn't leave the state machine
                // stuck in a non-Stopped state — but silently swallowing
                // the failure here is a real gap: MeshNodeState has no
                // way to represent "stopped, but the underlying session
                // may not have actually been released." Phase 2's
                // stress-test work item (§4 work item 7, start/stop/
                // process-death/restart) needs to actually exercise this
                // path on a device before it's trusted either way.
            }
        }

        _state.value = MeshNodeState.Stopped
    }

    override fun isRunning(): Boolean = state.value is MeshNodeState.Running
}
