package com.inscopelabs.abx.skylar.bootstrap

/**
 * Configuration settings for the request-signing credential Issuer.
 */
data class IssuerConfig(
    /**
     * Default validity duration for persistent caller credentials (e.g. 7 days).
     */
    val persistentDefaultTtlMs: Long = 7 * 24 * 60 * 60 * 1000L,

    /**
     * Default validity duration for ephemeral caller credentials (e.g. 15 minutes).
     */
    val ephemeralDefaultTtlMs: Long = 15 * 60 * 1000L,

    /**
     * Expected Cloudflare Access issuer URL.
     */
    val expectedCfIssuer: String = "https://team.cloudflareaccess.com",

    /**
     * Expected Cloudflare Access audience tag.
     */
    val expectedCfAudience: String = "skylar-edge-gateway-aud"
)
