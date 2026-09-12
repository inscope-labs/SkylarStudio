# Phase 2 — Fallback Design Note: Bundled Official Tailscale App

**Status:** Placeholder — only needed if the embedded `libtailscale`
approach (`TsnetMeshNode`) is judged unviable per the spike report's
Go/No-Go section.

## When this fallback applies

Per phased-development-plan Phase 2 §2 ("Fallback plan documentation"),
this is the documented alternative to an embedded userspace mesh node,
for use if `gomobile bind` (or an equivalent binding path) proves
unstable, unmaintainable, or otherwise unsuitable during the spike.

## Fallback shape (to be detailed if triggered)

Instead of Skylar Core embedding its own mesh node in-process, Skylar
would depend on the bundled/installed official Tailscale Android app
already being present and enrolled on the device, and would reach it
via:

- [ ] Confirm mechanism: Android intents / content provider / other
      IPC surface the official Tailscale app exposes for status queries
- [ ] Confirm whether the official app exposes any auth-key-driven
      enrollment automation, or whether enrollment becomes a manual,
      user-driven step outside Skylar's control
- [ ] Re-evaluate architecture doc §1's Lane A note ("Skylar Core's own
      presence on the mesh is provided by an embedded userspace mesh
      node") — this fallback would require an addendum to that note,
      logged in `skylar-context-gateway-architecture-addenda.md`, not a
      silent edit to the core architecture doc
- [ ] Re-evaluate key-material separation (architecture doc §5) — a
      device-wide Tailscale app is not solely Skylar's transport
      credential the way an embedded node's key would be

## Trade-offs (to be filled in if this path is taken)

- Loses: single-process control over the mesh node's lifecycle
- Loses: the ability to guarantee no VPN-slot usage (the official app
  may use `VpnService` in some configurations — this would need
  re-verification against the same no-VPN-privilege posture Phase 2
  currently requires of the embedded approach)
- Gains: removes the highest-uncertainty component of Phase 2 entirely

## Decision record

This note stays a placeholder unless the spike report's §7 verdict is
"no-go" or "conditional go." If triggered, the actual decision and its
rationale get logged as an entry in
`skylar-context-gateway-architecture-addenda.md`, since it would alter
architecture doc §1's Lane A implementation note.
