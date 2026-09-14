package com.inscopelabs.abx.skylar.bootstrap

import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * Validates Cloudflare Access edge identity assertions presented during
 * ephemeral caller OAuth2 credential exchanges (architecture §5).
 */
interface CloudflareAccessValidator {
    fun validate(
        identity: CloudflareAccessIdentity?,
        currentTime: Long = System.currentTimeMillis()
    ): Result<CloudflareAccessIdentity>
}

/**
 * Standard implementation verifying expected issuer, audience, and assertion window.
 */
class DefaultCloudflareAccessValidator(
    private val expectedIssuer: String = "https://team.cloudflareaccess.com",
    private val expectedAudience: String = "skylar-edge-gateway-aud"
) : CloudflareAccessValidator {

    override fun validate(
        identity: CloudflareAccessIdentity?,
        currentTime: Long
    ): Result<CloudflareAccessIdentity> {
        Logger.d(TAG, "Validating Cloudflare Access identity assertion")

        if (identity == null) {
            Logger.w(TAG, "Cloudflare Access identity assertion is missing")
            return Result.Error("Missing Cloudflare Access identity", errorCode = "CF_ACCESS_MISSING")
        }

        if (identity.identityToken.isBlank()) {
            Logger.w(TAG, "Cloudflare Access identity token is blank")
            return Result.Error("Invalid Cloudflare Access identity token", errorCode = "CF_ACCESS_TOKEN_INVALID")
        }

        if (identity.userEmail.isBlank()) {
            Logger.w(TAG, "Cloudflare Access user identity claim is missing")
            return Result.Error("Missing Cloudflare Access identity user claim", errorCode = "CF_ACCESS_CLAIM_MISSING")
        }

        if (expectedIssuer.isNotBlank() && identity.issuer != expectedIssuer) {
            Logger.w(TAG, "Cloudflare Access issuer mismatch: expected=$expectedIssuer, got=${identity.issuer}")
            return Result.Error("Cloudflare Access issuer mismatch", errorCode = "CF_ACCESS_ISSUER_MISMATCH")
        }

        if (expectedAudience.isNotBlank() && identity.audience != expectedAudience) {
            Logger.w(TAG, "Cloudflare Access audience mismatch: expected=$expectedAudience, got=${identity.audience}")
            return Result.Error("Cloudflare Access audience mismatch", errorCode = "CF_ACCESS_AUDIENCE_MISMATCH")
        }

        if (!identity.isValid(currentTime)) {
            Logger.w(TAG, "Cloudflare Access identity assertion has expired or is premature (now=$currentTime, valid=${identity.issuedAt}..${identity.expiresAt})")
            return Result.Error("Cloudflare Access assertion expired", errorCode = "CF_ACCESS_EXPIRED")
        }

        Logger.d(TAG, "Cloudflare Access identity verified for ${identity.userEmail}")
        return Result.Success(identity)
    }

    companion object {
        private const val TAG = "DefaultCloudflareAccessValidator"
    }
}
