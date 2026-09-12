package com.inscopelabs.abx.skylar.ipc.aidl;

/**
 * Protected AIDL interface for SFM (System File Manager / storage vault).
 * Enforces platform-level access control: only Skylar Core may invoke this surface.
 */
interface ISfmService {
    /**
     * Executes an authorized file/vault capability request within SFM.
     * Throws SecurityException if the caller UID does not match Skylar.
     *
     * @param capability The capability identifier (e.g., 'storage.read', 'storage.write', 'vault.execute')
     * @param paramsJson JSON-encoded parameter payload
     * @return JSON-encoded execution result
     */
    String executeCapability(String capability, String paramsJson);

    /**
     * Checks if SFM service is healthy and operational.
     */
    boolean isAvailable();

    /**
     * Returns remaining quota in bytes for the caller namespace.
     */
    long getStorageQuotaBytes(String callerNamespace);
}
