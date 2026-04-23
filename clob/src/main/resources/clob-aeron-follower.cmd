#
# usage: clob-aeron-follower.cmd <aeron_dir>
# description: follower node with sequencer-driven time, Archive locator, and snapshot tracking
#

source aeron-network.cmd $1
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7002
source -s time-synced.cmd
create /bus/schema com.core.clob.schema.ClobSchema

# Aeron bus client (external driver, sequencer-driven time)
create /bus com.core.platform.bus.aeron.AeronBusClient \
    follower @/bus/schema $aeron_dir $event_channel $event_stream $command_channel $command_stream follower01

# Archive locator (discover archive from event stream)
create /archiveLocator com.core.platform.bus.aeron.ArchiveLocator @/bus

# Snapshot index (track valid snapshots)
create /snapshotIndex com.core.platform.applications.snapshot.SnapshotIndex @/bus

# Printer
create print01a com.core.clob.applications.printer.ClobPrinter @/bus .
