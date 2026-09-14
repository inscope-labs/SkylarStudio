package com.inscopelabs.abx.skylar.bootstrap

import com.inscopelabs.abx.skylar.diagnostics.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for storage and lifecycle management of issued request-signing credentials.
 */
interface CredentialStore {
    fun save(credential: IssuedSigningCredential)
    fun get(credentialId: String): IssuedSigningCredential?
    fun getActiveForCaller(callerId: String, currentTime: Long = System.currentTimeMillis()): List<IssuedSigningCredential>
    fun revoke(credentialId: String): Boolean
    fun revokeAllForCaller(callerId: String): Int
    fun revokeAllByClass(callerClass: CallerClass): Int
    fun revokeAll(): Int
}

/**
 * Thread-safe in-memory credential storage implementation.
 */
class InMemoryCredentialStore : CredentialStore {
    private val credentials = ConcurrentHashMap<String, IssuedSigningCredential>()

    override fun save(credential: IssuedSigningCredential) {
        Logger.d(TAG, "Storing issued signing credential ${credential.credentialId} for caller=${credential.callerId}, class=${credential.callerClass}")
        credentials[credential.credentialId] = credential
    }

    override fun get(credentialId: String): IssuedSigningCredential? {
        return credentials[credentialId]
    }

    override fun getActiveForCaller(callerId: String, currentTime: Long): List<IssuedSigningCredential> {
        return credentials.values.filter { it.callerId == callerId && it.isValid(currentTime) }
    }

    override fun revoke(credentialId: String): Boolean {
        val existing = credentials[credentialId] ?: return false
        if (existing.isRevoked) return true
        val revoked = existing.copy(isRevoked = true)
        credentials[credentialId] = revoked
        Logger.i(TAG, "Revoked credential $credentialId for caller=${existing.callerId}")
        return true
    }

    override fun revokeAllForCaller(callerId: String): Int {
        var count = 0
        credentials.forEach { (id, cred) ->
            if (cred.callerId == callerId && !cred.isRevoked) {
                credentials[id] = cred.copy(isRevoked = true)
                count++
            }
        }
        Logger.i(TAG, "Revoked $count credentials for caller=$callerId")
        return count
    }

    override fun revokeAllByClass(callerClass: CallerClass): Int {
        var count = 0
        credentials.forEach { (id, cred) ->
            if (cred.callerClass == callerClass && !cred.isRevoked) {
                credentials[id] = cred.copy(isRevoked = true)
                count++
            }
        }
        Logger.i(TAG, "Revoked $count credentials for class=$callerClass")
        return count
    }

    override fun revokeAll(): Int {
        var count = 0
        credentials.forEach { (id, cred) ->
            if (!cred.isRevoked) {
                credentials[id] = cred.copy(isRevoked = true)
                count++
            }
        }
        Logger.w(TAG, "System-wide credential revocation executed: $count credentials revoked")
        return count
    }

    companion object {
        private const val TAG = "InMemoryCredentialStore"
    }
}
