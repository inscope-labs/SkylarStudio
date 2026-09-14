package com.inscopelabs.abx.skylar.bootstrap

/**
 * Request payload for the OAuth2 client-credentials-style token exchange (architecture §5).
 *
 * Ephemeral callers present client credentials along with their edge Cloudflare Access identity.
 */
data class OAuth2TokenRequest(
    val grantType: String = GRANT_TYPE_CLIENT_CREDENTIALS,
    val clientId: String,
    val clientSecret: String,
    val requestedScope: String,
    val cfAccessIdentity: CloudflareAccessIdentity?
) {
    companion object {
        const val GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials"
    }
}
