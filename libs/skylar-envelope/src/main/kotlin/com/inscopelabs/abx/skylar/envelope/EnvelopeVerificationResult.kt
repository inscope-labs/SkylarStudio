package com.inscopelabs.abx.skylar.envelope

/**
 * Result of [EnvelopeVerifier.verify].
 */
sealed class EnvelopeVerificationResult {
    data object Success : EnvelopeVerificationResult()
    data class Failure(val reason: String, val errorCode: String) : EnvelopeVerificationResult()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun failureOrNull(): Failure? = this as? Failure
}
