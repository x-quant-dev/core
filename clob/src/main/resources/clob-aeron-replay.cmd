#
# usage: clob-aeron-replay.cmd <aeron_dir>
# description: distributed replay node with sequencer-driven time in replay mode
#

source aeron-network.cmd $1
source -s sysout-log.cmd
source -s time-replay.cmd
create /bus/schema com.core.clob.schema.ClobSchema

# Aeron bus client (replay mode, time driven by event timestamps)
create /bus com.core.platform.bus.aeron.AeronBusClient \
    replay @/bus/schema $aeron_dir $event_channel $event_stream $command_channel $command_stream replay01

# Snapshot index (track valid snapshots)
create /snapshotIndex com.core.platform.applications.snapshot.SnapshotIndex @/bus

# Printer (to observe replayed events)
create print01a com.core.clob.applications.printer.ClobPrinter @/bus .
