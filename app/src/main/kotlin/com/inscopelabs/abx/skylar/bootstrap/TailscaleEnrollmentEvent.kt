package com.inscopelabs.abx.skylar.bootstrap

/**
 * Event representing the completion of Tailscale mesh enrollment by a caller device.
 *
 * Architecture doc §5:
 * "Persistent callers: the signing credential is auto-issued the moment the device
 * completes Tailscale mesh enrollment. Enrollment itself remains transport-only per §1
 * — this is a separate, second issuance step triggered by (not implied by) successful
 * enrollment, so a device that only has mesh reachability and no signing credential yet
 * still cannot produce a valid envelope."
 */
data class TailscaleEnrollmentEvent(
    val deviceId: String,
    val tailnetIp: String,
    val nodeKey: String,
    val callerId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = true
) {
    init {
        require(deviceId.isNotBlank()) { "deviceId cannot be blank" }
        require(callerId.isNotBlank()) { "callerId cannot be blank" }
    }
}
