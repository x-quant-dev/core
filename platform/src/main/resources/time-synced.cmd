#
# usage: time-synced.cmd
# description: replaces /vm/time with a SequencerDrivenTime in live mode
#              follower nodes will track sequencer time via event timestamps
# prerequisite: must be sourced before bus client creation
#

create /vm/systemClock com.core.infrastructure.time.CachedHighPrecisionTime
create /vm/time com.core.infrastructure.time.SequencerDrivenTime @/vm/systemClock
