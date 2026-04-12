#
# usage: consensus.cmd
# description: creates a lease-based consensus module for split-brain fencing
#              automatically wires to sequencer and promotion guard if they exist
#              source AFTER creating the sequencer and promotion guard
#

create /consensus com.core.platform.applications.sequencer.ConsensusModule
