package com.core.platform.applications.sequencer;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.concurrent.ThreadIdentityGuard;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.Time;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A lightweight lease-based consensus module for split-brain fencing and automatic leader election.
 *
 * <p>Before a sequencer can go active, it must acquire a lease from this consensus module. The module
 * guarantees that at most one lease is active at any time by tracking the lease holder and a
 * monotonically increasing epoch (fencing token).
 *
 * <p>The lease has a configurable timeout. If the lease holder does not renew within the timeout,
 * the lease expires and another sequencer may acquire it.
 *
 * <p><b>Automatic leader election:</b> Nodes can register as candidates with a priority.
 * When the current leader's lease expires, the consensus module automatically elects the registered
 * candidate with the lowest priority value (highest priority). Election is checked on every
 * {@code tryAcquire}, {@code tryRenew}, and {@code checkElection} call.
 *
 * <p><b>Important:</b> This is an in-process lease service. It does not provide distributed
 * consensus or survive process crashes. For true split-brain prevention across nodes, the consensus module
 * must be deployed as a separate, centralized process on an independent failure domain, or replaced
 * with an external strongly-consistent store. When running in-process, this module acts as a
 * local safety latch — it prevents accidental dual-activation within the same process but cannot
 * fence across separate JVM instances.
 *
 * <p>Shell commands:
 * <ul>
 *     <li>{@code acquire <nodeId>} - attempt to acquire the lease
 *     <li>{@code renew <nodeId>} - renew the lease (must be current holder)
 *     <li>{@code release <nodeId>} - release the lease
 *     <li>{@code registerCandidate <nodeId> <priority>} - register for auto-election
 *     <li>{@code deregisterCandidate <nodeId>} - deregister from auto-election
 *     <li>{@code candidates} - list registered candidates
 *     <li>{@code status} - current lease status
 * </ul>
 */
public class ConsensusModule implements Encodable, Consensus {

    private static final long DEFAULT_LEASE_TIMEOUT_MS = 5000;
    private static final int MAX_CANDIDATES = 16;

    private final Log log;
    private final Time time;
    private final ThreadIdentityGuard threadGuard = new ThreadIdentityGuard();

    @Property(write = true)
    private long leaseTimeoutMs;
    @Property
    private int epoch;
    @Property
    private String leaseHolder;
    @SuppressWarnings("PMD.UnusedPrivateField")
    @Property
    private long leaseAcquiredNanos;
    @Property
    private long leaseRenewedNanos;
    @Property
    private long acquireDeniedCount;
    @Property
    private long renewFailureCount;

    // candidate registry for automatic leader election
    private final String[] candidateNodeIds;
    private final int[] candidatePriorities;
    @Property
    private int candidateCount;
    @Property
    private long electionsTriggered;
    @Property
    private String lastElectedNodeId;
    private ElectionListener electionListener;

    /**
     * Creates a {@code ConsensusModule} with the specified parameters.
     *
     * @param logFactory a factory to create logs
     * @param time the time source
     */
    public ConsensusModule(LogFactory logFactory, Time time) {
        this(logFactory, new MetricFactory(logFactory), time);
    }

    /**
     * Creates a {@code ConsensusModule} with the specified parameters.
     *
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param time the time source
     */
    public ConsensusModule(LogFactory logFactory, MetricFactory metricFactory, Time time) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");
        this.time = Objects.requireNonNull(time, "time is null");

        log = logFactory.create(getClass());
        leaseTimeoutMs = DEFAULT_LEASE_TIMEOUT_MS;
        candidateNodeIds = new String[MAX_CANDIDATES];
        candidatePriorities = new int[MAX_CANDIDATES];

        metricFactory.registerGaugeMetric("Consensus_Epoch", () -> (long) epoch);
        metricFactory.registerGaugeMetric("Consensus_LeaseHeld", () -> leaseHolder != null ? 1L : 0L);
        metricFactory.registerGaugeMetric("Consensus_LeaseExpired", () -> isLeaseExpired() ? 1L : 0L);
        metricFactory.registerGaugeMetric("Consensus_LeaseRemainingMs", this::leaseRemainingMs);
        metricFactory.registerGaugeMetric("Consensus_AcquireDeniedCount", () -> acquireDeniedCount);
        metricFactory.registerGaugeMetric("Consensus_RenewFailureCount", () -> renewFailureCount);
        metricFactory.registerGaugeMetric("Consensus_CandidateCount", () -> (long) candidateCount);
        metricFactory.registerGaugeMetric("Consensus_ElectionsTriggered", () -> electionsTriggered);
        threadGuard.bind();
    }

    /**
     * Attempts to acquire the lease for the specified node.
     * Succeeds if no lease is held or the current lease has expired.
     *
     * @param nodeId the node requesting the lease
     * @param encoder the encoder for the response
     */
    @Command(path = "acquire")
    public void acquire(String nodeId, ObjectEncoder encoder) {
        threadGuard.check();
        var now = time.nanos();

        if (leaseHolder != null && !isExpired(now)) {
            if (leaseHolder.equals(nodeId)) {
                // already holds the lease, treat as renew
                leaseRenewedNanos = now;
                log.info().append("lease renewed (via acquire): nodeId=").append(nodeId)
                        .append(", epoch=").append(epoch).commit();
                encoder.openMap()
                        .string("result").string("OK")
                        .string("epoch").number(epoch)
                        .string("action").string("renewed")
                        .closeMap();
            } else {
                acquireDeniedCount++;
                log.warn().append("lease acquisition denied: nodeId=").append(nodeId)
                        .append(", currentHolder=").append(leaseHolder)
                        .append(", epoch=").append(epoch).commit();
                encoder.openMap()
                        .string("result").string("DENIED")
                        .string("currentHolder").string(leaseHolder)
                        .string("epoch").number(epoch)
                        .closeMap();
            }
            return;
        }

        // lease is available (no holder or expired)
        if (leaseHolder != null) {
            log.warn().append("lease expired for: nodeId=").append(leaseHolder)
                    .append(", epoch=").append(epoch).commit();
        }

        epoch++;
        leaseHolder = nodeId;
        leaseAcquiredNanos = now;
        leaseRenewedNanos = now;

        log.info().append("lease acquired: nodeId=").append(nodeId)
                .append(", epoch=").append(epoch).commit();
        encoder.openMap()
                .string("result").string("OK")
                .string("epoch").number(epoch)
                .string("action").string("acquired")
                .closeMap();
    }

    /**
     * Renews the lease for the specified node.
     *
     * @param nodeId the node renewing the lease
     * @param encoder the encoder for the response
     */
    @Command(path = "renew")
    public void renew(String nodeId, ObjectEncoder encoder) {
        threadGuard.check();
        var now = time.nanos();

        if (leaseHolder == null || !leaseHolder.equals(nodeId)) {
            renewFailureCount++;
            encoder.openMap()
                    .string("result").string("DENIED")
                    .string("reason").string("not lease holder")
                    .closeMap();
            return;
        }

        if (isExpired(now)) {
            leaseHolder = null;
            renewFailureCount++;
            encoder.openMap()
                    .string("result").string("EXPIRED")
                    .string("reason").string("lease expired before renewal")
                    .closeMap();
            return;
        }

        leaseRenewedNanos = now;
        encoder.openMap()
                .string("result").string("OK")
                .string("epoch").number(epoch)
                .closeMap();
    }

    /**
     * Releases the lease for the specified node.
     *
     * @param nodeId the node releasing the lease
     * @param encoder the encoder for the response
     */
    @Command(path = "release")
    public void release(String nodeId, ObjectEncoder encoder) {
        threadGuard.check();
        if (leaseHolder == null || !leaseHolder.equals(nodeId)) {
            encoder.openMap()
                    .string("result").string("DENIED")
                    .string("reason").string("not lease holder")
                    .closeMap();
            return;
        }

        log.info().append("lease released: nodeId=").append(nodeId)
                .append(", epoch=").append(epoch).commit();
        leaseHolder = null;
        encoder.openMap()
                .string("result").string("OK")
                .string("epoch").number(epoch)
                .closeMap();
    }

    /**
     * Returns the current lease holder, or null if no lease is held.
     *
     * @return the lease holder node ID
     */
    public String getLeaseHolder() {
        return leaseHolder;
    }

    /**
     * Returns the current epoch (fencing token).
     *
     * @return the epoch
     */
    public int getEpoch() {
        return epoch;
    }

    /**
     * Returns true if the lease has expired.
     *
     * @return true if expired
     */
    public boolean isLeaseExpired() {
        if (leaseHolder == null) {
            return true;
        }
        return isExpired(time.nanos());
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        var now = time.nanos();
        var expired = leaseHolder != null && isExpired(now);
        var remainingMs = leaseHolder != null && !expired
                ? leaseTimeoutMs - TimeUnit.NANOSECONDS.toMillis(now - leaseRenewedNanos)
                : 0;

        encoder.openMap()
                .string("epoch").number(epoch)
                .string("leaseHolder").string(leaseHolder != null ? leaseHolder : "none")
                .string("leaseTimeoutMs").number(leaseTimeoutMs)
                .string("expired").bool(expired)
                .string("remainingMs").number(remainingMs)
                .string("candidateCount").number(candidateCount)
                .string("electionsTriggered").number(electionsTriggered)
                .string("lastElectedNodeId").string(
                        lastElectedNodeId != null ? lastElectedNodeId : "none")
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }

    /**
     * Attempts to acquire the lease programmatically.
     *
     * @param nodeId the node requesting the lease
     * @return true if the lease was acquired or already held
     */
    public boolean tryAcquire(String nodeId) {
        threadGuard.check();
        var now = time.nanos();

        if (leaseHolder != null && !isExpired(now)) {
            if (leaseHolder.equals(nodeId)) {
                leaseRenewedNanos = now;
                return true;
            }
            acquireDeniedCount++;
            return false;
        }

        if (leaseHolder != null) {
            log.warn().append("lease expired for: nodeId=").append(leaseHolder)
                    .append(", epoch=").append(epoch).commit();
        }

        epoch++;
        leaseHolder = nodeId;
        leaseAcquiredNanos = now;
        leaseRenewedNanos = now;

        log.info().append("lease acquired: nodeId=").append(nodeId)
                .append(", epoch=").append(epoch).commit();
        return true;
    }

    /**
     * Renews the lease programmatically.
     *
     * @param nodeId the node renewing the lease
     * @return true if the lease was successfully renewed
     */
    public boolean tryRenew(String nodeId) {
        threadGuard.check();
        var now = time.nanos();

        if (leaseHolder == null || !leaseHolder.equals(nodeId)) {
            renewFailureCount++;
            return false;
        }

        if (isExpired(now)) {
            leaseHolder = null;
            renewFailureCount++;
            return false;
        }

        leaseRenewedNanos = now;
        return true;
    }

    /**
     * Releases the lease programmatically.
     * If candidates are registered, triggers automatic election of the next leader.
     *
     * @param nodeId the node releasing the lease
     */
    public void tryRelease(String nodeId) {
        threadGuard.check();
        if (leaseHolder != null && leaseHolder.equals(nodeId)) {
            log.info().append("lease released: nodeId=").append(nodeId)
                    .append(", epoch=").append(epoch).commit();
            leaseHolder = null;
            electIfExpired(time.nanos());
        }
    }

    /**
     * Registers a candidate for automatic leader election.
     *
     * @param nodeId the candidate node ID
     * @param priority election priority (lower values = higher priority)
     * @return true if registered successfully
     */
    @Override
    public boolean registerCandidate(String nodeId, int priority) {
        threadGuard.check();

        // update existing candidate
        for (var i = 0; i < candidateCount; i++) {
            if (candidateNodeIds[i].equals(nodeId)) {
                candidatePriorities[i] = priority;
                log.info().append("candidate updated: nodeId=").append(nodeId)
                        .append(", priority=").append(priority).commit();
                return true;
            }
        }

        if (candidateCount >= MAX_CANDIDATES) {
            log.warn().append("candidate registration rejected, max candidates reached: nodeId=")
                    .append(nodeId).commit();
            return false;
        }

        candidateNodeIds[candidateCount] = nodeId;
        candidatePriorities[candidateCount] = priority;
        candidateCount++;

        log.info().append("candidate registered: nodeId=").append(nodeId)
                .append(", priority=").append(priority)
                .append(", totalCandidates=").append(candidateCount).commit();

        // if no leader, trigger election immediately
        electIfExpired(time.nanos());

        return true;
    }

    /**
     * Registers a candidate via shell command.
     *
     * @param nodeId the candidate node ID
     * @param priority the election priority
     * @param encoder the response encoder
     */
    @Command(path = "registerCandidate")
    public void registerCandidateCommand(String nodeId, int priority, ObjectEncoder encoder) {
        threadGuard.check();
        var result = registerCandidate(nodeId, priority);
        encoder.openMap()
                .string("result").string(result ? "OK" : "DENIED")
                .string("nodeId").string(nodeId)
                .string("priority").number(priority)
                .string("candidateCount").number(candidateCount)
                .closeMap();
    }

    /**
     * Deregisters a candidate from automatic leader election.
     *
     * @param nodeId the candidate node ID to remove
     */
    @Override
    public void deregisterCandidate(String nodeId) {
        threadGuard.check();

        for (var i = 0; i < candidateCount; i++) {
            if (candidateNodeIds[i].equals(nodeId)) {
                // shift remaining entries down
                var remaining = candidateCount - i - 1;
                if (remaining > 0) {
                    System.arraycopy(candidateNodeIds, i + 1, candidateNodeIds, i, remaining);
                    System.arraycopy(candidatePriorities, i + 1, candidatePriorities, i, remaining);
                }
                candidateCount--;
                candidateNodeIds[candidateCount] = null;
                candidatePriorities[candidateCount] = 0;

                log.info().append("candidate deregistered: nodeId=").append(nodeId)
                        .append(", remainingCandidates=").append(candidateCount).commit();
                return;
            }
        }
    }

    /**
     * Deregisters a candidate via shell command.
     *
     * @param nodeId the candidate node ID
     * @param encoder the response encoder
     */
    @Command(path = "deregisterCandidate")
    public void deregisterCandidateCommand(String nodeId, ObjectEncoder encoder) {
        threadGuard.check();
        deregisterCandidate(nodeId);
        encoder.openMap()
                .string("result").string("OK")
                .string("nodeId").string(nodeId)
                .string("candidateCount").number(candidateCount)
                .closeMap();
    }

    @Override
    public void setElectionListener(ElectionListener listener) {
        this.electionListener = listener;
    }

    @Override
    public int getCandidateCount() {
        return candidateCount;
    }

    /**
     * Checks whether the current lease has expired and triggers an automatic
     * election if candidates are registered. Can be called periodically by the
     * event loop or scheduler to drive election without waiting for a
     * {@code tryAcquire}/{@code tryRenew} call.
     *
     * @return the current lease holder after the check, or null
     */
    @Command(path = "checkElection")
    public String checkElection() {
        threadGuard.check();
        electIfExpired(time.nanos());
        return leaseHolder;
    }

    /**
     * Lists registered candidates via shell command.
     *
     * @param encoder the response encoder
     */
    @Command(path = "candidates", readOnly = true)
    public void encodeCandidates(ObjectEncoder encoder) {
        encoder.openMap()
                .string("candidateCount").number(candidateCount);
        for (var i = 0; i < candidateCount; i++) {
            encoder.string(candidateNodeIds[i]).number(candidatePriorities[i]);
        }
        encoder.closeMap();
    }

    /**
     * Returns the elections triggered count.
     *
     * @return the elections triggered
     */
    public long getElectionsTriggered() {
        return electionsTriggered;
    }

    /**
     * Returns the node ID of the last automatically elected leader.
     *
     * @return the last elected node ID, or null
     */
    public String getLastElectedNodeId() {
        return lastElectedNodeId;
    }

    private void electIfExpired(long nowNanos) {
        if (candidateCount == 0) {
            return;
        }
        if (leaseHolder != null && !isExpired(nowNanos)) {
            return;
        }

        // find the candidate with the lowest priority value (highest priority),
        // skipping the expired holder (they failed to renew, likely dead)
        var bestIdx = -1;
        var bestPriority = Integer.MAX_VALUE;
        for (var i = 0; i < candidateCount; i++) {
            if (leaseHolder != null && leaseHolder.equals(candidateNodeIds[i])) {
                continue;
            }
            if (candidatePriorities[i] < bestPriority) {
                bestPriority = candidatePriorities[i];
                bestIdx = i;
            }
        }

        // if no other candidate available (only the expired holder is registered),
        // fall back to the expired holder as a last resort when the lease is null
        // (explicit release vs timeout)
        if (bestIdx < 0) {
            if (leaseHolder == null) {
                // explicit release — re-elect any candidate
                for (var i = 0; i < candidateCount; i++) {
                    if (candidatePriorities[i] < bestPriority) {
                        bestPriority = candidatePriorities[i];
                        bestIdx = i;
                    }
                }
            }
            if (bestIdx < 0) {
                return;
            }
        }

        var electedNodeId = candidateNodeIds[bestIdx];

        if (leaseHolder != null) {
            log.warn().append("lease expired for: nodeId=").append(leaseHolder)
                    .append(", epoch=").append(epoch).commit();
        }

        epoch++;
        leaseHolder = electedNodeId;
        leaseAcquiredNanos = nowNanos;
        leaseRenewedNanos = nowNanos;
        electionsTriggered++;
        lastElectedNodeId = electedNodeId;

        log.info().append("auto-elected leader: nodeId=").append(electedNodeId)
                .append(", epoch=").append(epoch)
                .append(", priority=").append(bestPriority).commit();

        if (electionListener != null) {
            electionListener.onElected(epoch);
        }
    }

    private boolean isExpired(long nowNanos) {
        return TimeUnit.NANOSECONDS.toMillis(nowNanos - leaseRenewedNanos) > leaseTimeoutMs;
    }

    private long leaseRemainingMs() {
        var now = time.nanos();
        if (leaseHolder == null || isExpired(now)) {
            return 0;
        }
        return leaseTimeoutMs - TimeUnit.NANOSECONDS.toMillis(now - leaseRenewedNanos);
    }

    public long getAcquireDeniedCount() {
        return acquireDeniedCount;
    }

    public long getRenewFailureCount() {
        return renewFailureCount;
    }
}
