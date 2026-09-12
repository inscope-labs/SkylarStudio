package com.inscopelabs.abx.skylar.ipc.aidl;

/**
 * Protected AIDL interface for Starlight (UI execution & accessibility agent).
 * Enforces platform-level access control: only Skylar Core may invoke this surface.
 */
interface IStarlightService {
    /**
     * Executes an authorized capability request within Starlight.
     * Throws SecurityException if the caller UID does not match Skylar.
     *
     * @param capability The capability identifier (e.g., 'context.query', 'starlight.workflow.start')
     * @param paramsJson JSON-encoded parameter payload
     * @return JSON-encoded execution result or consent-pending descriptor
     */
    String executeCapability(String capability, String paramsJson);

    /**
     * Checks if Starlight service is healthy and operational.
     */
    boolean isAvailable();

    /**
     * Checks if the given capability requires explicit user approval via Request Inbox.
     */
    boolean requiresUserConsent(String capability);

    /**
     * Approves a pending workflow item in Starlight's Request Inbox.
     */
    boolean approveWorkflow(String workflowId);
}
