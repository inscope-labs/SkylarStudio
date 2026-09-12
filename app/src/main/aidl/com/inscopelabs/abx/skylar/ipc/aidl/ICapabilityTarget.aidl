package com.inscopelabs.abx.skylar.ipc.aidl;

/**
 * Common AIDL contract for on-device Capability Targets co-resident with Skylar Core.
 * All target endpoints require platform permission enforcement (signature permission / UID validation).
 */
interface ICapabilityTarget {
    /**
     * Executes an authorized capability with a JSON payload.
     * Throws SecurityException if the caller UID does not match Skylar.
     */
    String executeCapability(String capability, String paramsJson);

    /**
     * Checks if the target service is healthy and operational.
     */
    boolean isAvailable();

    /**
     * Returns whether the requested capability requires user-consent before execution.
     */
    boolean requiresUserConsent(String capability);
}
