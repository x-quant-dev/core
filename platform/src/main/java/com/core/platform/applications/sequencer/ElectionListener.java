package com.core.platform.applications.sequencer;

/**
 * Listener invoked when a candidate wins a leader election.
 */
@FunctionalInterface
public interface ElectionListener {

    /**
     * Called when this node has been elected as leader.
     *
     * @param epoch the new leader epoch
     */
    void onElected(int epoch);
}
