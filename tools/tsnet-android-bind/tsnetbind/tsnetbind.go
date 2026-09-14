// Package tsnetbind is a gomobile-bindable wrapper around tailscale.com/tsnet.
//
// ============================== WHY THIS EXISTS ==============================
// gomobile bind was pointed directly at tailscale.com/tsnet and failed:
//
//	gobind: ... too many result values:
//	func (*tailscale.com/tsnet.Server).Loopback() (addr string, proxyCred string, localAPICred string, err error)
//
// gobind requires every bound function/method to return at most one
// non-error value. gomobile bind also binds a package's entire exported
// surface, not just the symbols you actually use — so the only way past
// this is to bind a wrapper package with a gobind-compatible surface,
// rather than tsnet directly. That's what this package is.
//
// ============================ WHAT'S DELIBERATELY NOT HERE ===================
// Loopback() (and anything else Phase 2 doesn't need) is intentionally not
// wrapped. Phase 2's scope (phased-development-plan.md, Phase 2 §2) is
// start/stop/status/auth-key injection only — the local SOCKS/HTTP proxy
// credentials Loopback() returns aren't part of this phase. Add wrappers
// for more of tsnet's surface only when a later phase actually needs them,
// not preemptively.
//
// ============================ VERIFICATION STATUS =============================
// The method signatures below (Server.Start, Server.Up, ipnstate.Status
// shape) are written from general knowledge of the tsnet API, not
// cross-checked against the actual tailscale.com/tsnet source — this
// sandbox has no network path to fetch it. This package has NOT been
// compiled anywhere yet. CI resolving the real module and running
// `gomobile bind` against this package is the actual verification step;
// if any signature here is wrong, CI will fail with a concrete compile
// error rather than this silently claiming to work. Treat a clean CI run
// as the first real evidence this package is correct, not this comment.
// ===============================================================================
package tsnetbind

import (
	"context"
	"fmt"

	"tailscale.com/tsnet"
)

// Server wraps a tsnet.Server with a gobind-compatible surface.
type Server struct {
	srv *tsnet.Server
}

// New constructs a Server. Nothing is started yet — call Start.
//
//   - hostname: the name this node presents on the tailnet.
//   - authKey: short-lived Tailscale auth key (Phase 5/6 Issuer output).
//   - stateDir: local directory for tsnet's persistent state. Must be a
//     location the Android process can actually write to (e.g. the app's
//     filesDir) — not decided/wired in from the Kotlin side yet.
func New(hostname, authKey, stateDir string) *Server {
	return &Server{
		srv: &tsnet.Server{
			Hostname: hostname,
			AuthKey:  authKey,
			Dir:      stateDir,
		},
	}
}

// Start brings the node up and blocks until it has joined the tailnet or
// failed. Returns the node's assigned Tailscale IPv4 address as a string
// on success — TsnetMeshNode.kt's Kotlin side maps this directly into
// MeshNodeState.Running's tailscaleIp field.
//
// Deliberately returns (string, error) rather than the full status object:
// keeping the gobind surface minimal and easy to keep binding-compatible
// as tsnet's own API evolves. Expand this only if a later phase needs more
// of the status than just the IP.
func (s *Server) Start() (string, error) {
	ctx := context.Background()

	if err := s.srv.Start(); err != nil {
		return "", fmt.Errorf("tsnet Start: %w", err)
	}

	status, err := s.srv.Up(ctx)
	if err != nil {
		return "", fmt.Errorf("tsnet Up: %w", err)
	}
	if len(status.TailscaleIPs) == 0 {
		return "", fmt.Errorf("tsnet Up succeeded but no Tailscale IP was assigned")
	}
	return status.TailscaleIPs[0].String(), nil
}

// Stop tears the node down and releases its tailnet session.
func (s *Server) Stop() error {
	return s.srv.Close()
}
