#
# usage: time-replay.cmd
# description: replaces /vm/time with a SequencerDrivenTime in replay mode
#              purely driven by event timestamps for deterministic distributed replay
# prerequisite: must be sourced before bus client creation
#

create /vm/time com.core.infrastructure.time.SequencerDrivenTime
