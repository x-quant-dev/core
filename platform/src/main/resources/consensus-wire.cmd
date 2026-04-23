#
# usage: consensus-wire.cmd <sequencer_path>
# description: wires the consensus module to a sequencer and promotion guard
# params:
#   1: sequencer shell path (e.g., seq01a, seq01b)
# prerequisite: consensus.cmd and promotion-guard.cmd must be sourced first
#

$1/setConsensusModule @/consensus
/promotionGuard/setConsensusModule @/consensus
