# Tailscale Enrollment Notes for OCI Relay Forwarder

## Node Provisioning

1. Tag allocation:
   The OCI VM node is enrolled with `--advertise-tags=tag:relay-forwarder`.
   
2. Auth key creation:
   ```bash
   tailscale up --authkey=tskey-auth-xxxxxx --advertise-tags=tag:relay-forwarder --reset
   ```

3. Verification:
   Confirm node registration:
   ```bash
   tailscale status
   tailscale netcheck
   ```

4. ACL Enforcement:
   Per `acl-policy.hujson`, `tag:relay-forwarder` has strict network boundaries: it can only dial destination `tag:skylar-gateway:8443`.
   Attempts to access other nodes or other ports are dropped by Tailscale's WireGuard layer.
