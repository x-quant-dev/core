package com.core.platform.applications.sequencer;

/**
 * Consensus interface for lease-based fencing and leader election.
 *
 * <p>Provides both explicit lease acquisition (manual fencing) and
 * automatic leader election via candidate registration. When candidates
 * are registered, the consensus module can automatically elect a new leader
 * when the current lease expires.
 */
public interface Consensus {

    /**
     * Attempts to acquire the lease for the specified node.
     *
     * @param nodeId the node requesting the lease
     * @return true if the lease was acquired
     */
    boolean tryAcquire(String nodeId);

    /**
     * Renews the lease for the specified node.
     *
     * @param nodeId the node renewing the lease
     * @return true if the lease was renewed
     */
    boolean tryRenew(String nodeId);

    /**
     * Releases the lease for the specified node.
     *
     * @param nodeId the node releasing the lease
     */
    void tryRelease(String nodeId);

    /**
     * Returns the current lease holder, or null if no lease is held.
     *
     * @return the lease holder node ID
     */
    String getLeaseHolder();

    /**
     * Returns the current epoch (fencing token).
     *
     * @return the epoch
     */
    int getEpoch();

    /**
     * Returns true if the lease has expired or no lease is held.
     *
     * @return true if expired
     */
    boolean isLeaseExpired();

    /**
     * Registers a node as a candidate for automatic leader election.
     * When the current leader's lease expires, the consensus module will automatically
     * elect the registered candidate with the lowest (highest priority) priority value.
     *
     * <p>If the node is already registered, the priority is updated.
     *
     * @param nodeId the candidate node ID
     * @param priority the election priority (lower values = higher priority)
     * @return true if registration succeeded
     */
    default boolean registerCandidate(String nodeId, int priority) {
        return false;
    }

    /**
     * Deregisters a node from automatic leader election.
     *
     * @param nodeId the candidate node ID to remove
     */
    default void deregisterCandidate(String nodeId) {
    }

    /**
     * Sets a listener that is invoked when this node is elected as leader.
     * The listener receives the new epoch.
     *
     * @param listener the election listener, or null to clear
     */
    default void setElectionListener(ElectionListener listener) {
    }

    /**
     * Returns the number of registered candidates.
     *
     * @return the candidate count
     */
    default int getCandidateCount() {
        return 0;
    }
}
