package com.inscopelabs.abx.skylar.bootstrap

/**
 * Caller identity classes defined in architecture doc §0 and §5.
 *
 * Identity class is an intrinsic property of the credential and its issuance
 * flow, NEVER of the transport or ingress lane over which a request arrives.
 */
enum class CallerClass {
    /**
     * Long-lived, individually provisioned caller (e.g. John's trusted devices,
     * dev laptops, the OCI VM itself).
     *
     * Signing credential is automatically issued upon confirmed Tailscale mesh
     * enrollment completion, scoped per the caller's authorization matrix entry,
     * and rotated on a periodic cadence (e.g. 7 days).
     */
    PERSISTENT,

    /**
     * Short-lived, self-service issued caller (e.g. LLM agents, CI runners,
     * third-party plugin developers).
     *
     * Signing credential is issued via an OAuth2 client-credentials exchange against
     * the Issuer, validating both pre-registered client credentials AND Cloudflare
     * Access identity assertions together. Credential has a short TTL (e.g. 15 minutes)
     * and strictly bounded scopes.
     */
    EPHEMERAL
}
