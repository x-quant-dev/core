package com.core.platform.applications.sequencer;

import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.ManualTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.BDDAssertions.then;

public class ConsensusElectionTest {

    private ConsensusModule consensusModule;
    private ManualTime time;

    @BeforeEach
    void before_each() {
        time = new ManualTime(LocalTime.of(9, 30));
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        consensusModule = new ConsensusModule(logFactory, metricFactory, time);
    }

    @Test
    void register_candidate_succeeds() {
        then(consensusModule.registerCandidate("nodeA", 1)).isTrue();
        then(consensusModule.getCandidateCount()).isEqualTo(1);
    }

    @Test
    void register_multiple_candidates() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);
        consensusModule.registerCandidate("nodeC", 3);

        then(consensusModule.getCandidateCount()).isEqualTo(3);
    }

    @Test
    void register_updates_existing_candidate_priority() {
        consensusModule.registerCandidate("nodeA", 5);
        consensusModule.registerCandidate("nodeA", 1);

        then(consensusModule.getCandidateCount()).isEqualTo(1);
    }

    @Test
    void deregister_candidate() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);

        consensusModule.deregisterCandidate("nodeA");

        then(consensusModule.getCandidateCount()).isEqualTo(1);
    }

    @Test
    void deregister_nonexistent_candidate_is_no_op() {
        consensusModule.registerCandidate("nodeA", 1);

        consensusModule.deregisterCandidate("nodeX");

        then(consensusModule.getCandidateCount()).isEqualTo(1);
    }

    @Test
    void auto_elects_when_no_leader_and_candidate_registers() {
        consensusModule.registerCandidate("nodeA", 1);

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");
        then(consensusModule.getEpoch()).isEqualTo(1);
        then(consensusModule.getElectionsTriggered()).isEqualTo(1);
        then(consensusModule.getLastElectedNodeId()).isEqualTo("nodeA");
    }

    @Test
    void auto_elects_highest_priority_candidate() {
        // register low-priority first
        consensusModule.registerCandidate("nodeC", 10);

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeC");

        // let lease expire, register higher-priority candidate
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.registerCandidate("nodeA", 1);

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");
        then(consensusModule.getLastElectedNodeId()).isEqualTo("nodeA");
    }

    @Test
    void auto_elects_on_lease_expiry_via_check_election() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");
        var epoch1 = consensusModule.getEpoch();

        // expire nodeA's lease
        time.advanceTime(Duration.ofMillis(5001));

        // nodeA should not be re-elected (it failed to renew)
        // the next best candidate is nodeB
        consensusModule.checkElection();

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeB");
        then(consensusModule.getEpoch()).isGreaterThan(epoch1);
        then(consensusModule.getElectionsTriggered()).isEqualTo(2);
    }

    @Test
    void auto_elects_on_explicit_release() {
        consensusModule.registerCandidate("nodeA", 2);
        consensusModule.registerCandidate("nodeB", 1);

        // nodeA is elected first (first candidate when no leader)
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        // nodeA voluntarily releases — nodeB has higher priority (lower value)
        // so nodeB should be elected
        consensusModule.tryRelease("nodeA");

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeB");
        then(consensusModule.getElectionsTriggered()).isEqualTo(2);
    }

    @Test
    void no_election_when_lease_is_active() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        // advance time but NOT past lease timeout
        time.advanceTime(Duration.ofMillis(3000));
        consensusModule.checkElection();

        // no change
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");
        then(consensusModule.getElectionsTriggered()).isEqualTo(1);
    }

    @Test
    void election_listener_fires_on_election() {
        var electedEpoch = new AtomicInteger(-1);
        consensusModule.setElectionListener(electedEpoch::set);

        consensusModule.registerCandidate("nodeA", 1);

        then(electedEpoch.get()).isEqualTo(1);
    }

    @Test
    void election_listener_fires_on_failover() {
        var electedEpoch = new AtomicInteger(-1);
        consensusModule.setElectionListener(electedEpoch::set);

        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);

        // first election for nodeA
        then(electedEpoch.get()).isEqualTo(1);

        // expire and failover to nodeB
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();

        then(electedEpoch.get()).isEqualTo(2);
    }

    @Test
    void manual_acquire_still_works_with_candidates() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);

        // nodeA was auto-elected, now nodeA renews manually
        then(consensusModule.tryRenew("nodeA")).isTrue();

        // manual acquire by a non-candidate also works when lease expires
        time.advanceTime(Duration.ofMillis(5001));
        then(consensusModule.tryAcquire("nodeX")).isTrue();
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeX");
    }

    @Test
    void deregistering_leader_and_releasing_elects_next() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        // deregister nodeA and release
        consensusModule.deregisterCandidate("nodeA");
        consensusModule.tryRelease("nodeA");

        // only nodeB remains as candidate
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeB");
        then(consensusModule.getCandidateCount()).isEqualTo(1);
    }

    @Test
    void priority_update_affects_next_election() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);

        // nodeA wins the first election (first candidate when no leader)
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        // expire nodeA's lease — nodeB should be elected (only other candidate)
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeB");

        // now update nodeA to highest priority and expire nodeB
        consensusModule.registerCandidate("nodeA", 0);
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();

        // nodeA should now be elected (priority 0 beats nodeB's priority 2)
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");
    }

    @Test
    void no_election_when_no_candidates() {
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();

        then(consensusModule.getLeaseHolder()).isNull();
        then(consensusModule.getElectionsTriggered()).isEqualTo(0);
    }

    @Test
    void expired_holder_is_not_re_elected_skips_to_next() {
        consensusModule.registerCandidate("nodeA", 1);

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        // expire nodeA's lease — nodeA is the only candidate but failed to renew
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();

        // nodeA should NOT be re-elected (it failed to renew, probably dead)
        // since no other candidate exists, lease holder should remain nodeA (expired)
        // but electIfExpired skips re-electing the expired holder
        then(consensusModule.getElectionsTriggered()).isEqualTo(1);
    }

    @Test
    void status_includes_election_fields() {
        consensusModule.registerCandidate("nodeA", 1);

        var status = consensusModule.toString();

        then(status).contains("candidateCount");
        then(status).contains("electionsTriggered");
        then(status).contains("lastElectedNodeId");
    }

    @Test
    void epoch_increments_on_each_election() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);

        then(consensusModule.getEpoch()).isEqualTo(1);

        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getEpoch()).isEqualTo(2);

        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getEpoch()).isEqualTo(3);
    }

    @Test
    void election_alternates_between_two_candidates_on_repeated_expiry() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        // expire nodeA → nodeB wins (skips expired holder)
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeB");

        // expire nodeB → nodeA wins (skips expired holder)
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        then(consensusModule.getElectionsTriggered()).isEqualTo(3);
    }

    @Test
    void renew_prevents_election() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        // renew before timeout
        time.advanceTime(Duration.ofMillis(4000));
        then(consensusModule.tryRenew("nodeA")).isTrue();

        // advance past original timeout but not past renewal
        time.advanceTime(Duration.ofMillis(2000));
        consensusModule.checkElection();

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");
        then(consensusModule.getElectionsTriggered()).isEqualTo(1);
    }

    @Test
    void max_candidates_rejects_overflow() {
        for (var i = 0; i < 16; i++) {
            then(consensusModule.registerCandidate("node" + i, i + 1)).isTrue();
        }
        then(consensusModule.getCandidateCount()).isEqualTo(16);

        then(consensusModule.registerCandidate("overflow", 99)).isFalse();
        then(consensusModule.getCandidateCount()).isEqualTo(16);
    }

    @Test
    void deregister_middle_candidate_shifts_remaining() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);
        consensusModule.registerCandidate("nodeC", 3);

        consensusModule.deregisterCandidate("nodeB");

        then(consensusModule.getCandidateCount()).isEqualTo(2);

        // expire nodeA → nodeC should be elected (nodeB was removed)
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeC");
    }

    @Test
    void election_with_equal_priorities_picks_first_registered() {
        consensusModule.registerCandidate("nodeA", 5);
        consensusModule.registerCandidate("nodeB", 5);

        // nodeA is elected first (first candidate when no leader)
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        // expire → both have equal priority, but nodeB is picked
        // because nodeA (expired holder) is skipped
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeB");
    }

    @Test
    void check_election_returns_current_holder() {
        consensusModule.registerCandidate("nodeA", 1);

        then(consensusModule.checkElection()).isEqualTo("nodeA");
    }

    @Test
    void check_election_returns_null_when_no_candidates() {
        then(consensusModule.checkElection()).isNull();
    }

    @Test
    void released_leader_can_be_re_elected_as_sole_candidate() {
        consensusModule.registerCandidate("nodeA", 1);
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        // explicit release with nodeA as the only candidate
        consensusModule.tryRelease("nodeA");

        // nodeA should be re-elected (release allows re-election)
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");
        then(consensusModule.getElectionsTriggered()).isEqualTo(2);
    }

    @Test
    void listener_not_fired_when_no_election_occurs() {
        var callCount = new AtomicInteger(0);
        consensusModule.setElectionListener(epoch -> callCount.incrementAndGet());

        consensusModule.checkElection();

        then(callCount.get()).isEqualTo(0);
    }

    @Test
    void multiple_failovers_track_elections_triggered() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);
        consensusModule.registerCandidate("nodeC", 3);

        then(consensusModule.getElectionsTriggered()).isEqualTo(1);

        // failover 1: A expires → B wins (lowest priority among non-expired)
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeB");
        then(consensusModule.getElectionsTriggered()).isEqualTo(2);

        // failover 2: B expires → between A(1) and C(3), A wins
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");
        then(consensusModule.getElectionsTriggered()).isEqualTo(3);
    }

    @Test
    void deregister_all_candidates_prevents_election() {
        consensusModule.registerCandidate("nodeA", 1);
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        // deregister first, then release — no candidates remain for election
        consensusModule.deregisterCandidate("nodeA");
        consensusModule.tryRelease("nodeA");

        then(consensusModule.getCandidateCount()).isEqualTo(0);
        then(consensusModule.getLeaseHolder()).isNull();

        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getLeaseHolder()).isNull();
    }

    @Test
    void manual_acquire_does_not_affect_candidate_registry() {
        consensusModule.registerCandidate("nodeA", 1);
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        // manual acquire by non-candidate when lease expires
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.tryAcquire("nodeX");
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeX");

        // candidate count unchanged
        then(consensusModule.getCandidateCount()).isEqualTo(1);

        // when nodeX expires, election picks from candidates
        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");
    }

    @Test
    void election_listener_receives_incrementing_epochs() {
        var epochs = new int[3];
        var idx = new AtomicInteger(0);
        consensusModule.setElectionListener(epoch -> {
            var i = idx.getAndIncrement();
            if (i < epochs.length) {
                epochs[i] = epoch;
            }
        });

        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);

        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();

        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();

        then(epochs[0]).isEqualTo(1);
        then(epochs[1]).isEqualTo(2);
        then(epochs[2]).isEqualTo(3);
        then(epochs[0]).isLessThan(epochs[1]);
        then(epochs[1]).isLessThan(epochs[2]);
    }

    @Test
    void release_by_non_holder_does_not_trigger_election() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");

        consensusModule.tryRelease("nodeB");

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");
        then(consensusModule.getElectionsTriggered()).isEqualTo(1);
    }

    @Test
    void last_elected_node_id_tracks_most_recent_winner() {
        consensusModule.registerCandidate("nodeA", 1);
        consensusModule.registerCandidate("nodeB", 2);
        consensusModule.registerCandidate("nodeC", 3);

        then(consensusModule.getLastElectedNodeId()).isEqualTo("nodeA");

        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getLastElectedNodeId()).isEqualTo("nodeB");

        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.checkElection();
        then(consensusModule.getLastElectedNodeId()).isEqualTo("nodeA");
    }
}
