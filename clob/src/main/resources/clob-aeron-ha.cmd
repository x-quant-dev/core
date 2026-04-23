#
# usage: clob-aeron-ha.cmd <aeron_dir> <archive_dir>
# description: clob demo with Aeron transport, embedded Archive, and HA support
#

source aeron-network.cmd $1
source -s aeron-embedded.cmd $aeron_dir
source -s aeron-archive.cmd $2
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7001
create /bus/schema com.core.clob.schema.ClobSchema

# Aeron bus client (embedded driver)
create /bus com.core.platform.bus.aeron.AeronBusClient \
    client @/bus/schema @/vm/aeron/driver $event_channel $event_stream $command_channel $command_stream $session_name

# Aeron bus server (embedded driver)
create /busServer com.core.platform.bus.aeron.AeronBusServer \
    server @/bus/schema @/vm/aeron/driver $event_channel $event_stream $command_channel $command_stream $session_name

# Archive locator (event-stream discovery)
create /archiveLocator com.core.platform.bus.aeron.ArchiveLocator @/bus

# Sequencer
create seq01a com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
create seq01a/handlers com.core.clob.applications.sequencer.ClobCommandHandlers @/busServer

# Archive announcer (publishes ArchiveAnnouncement on event stream)
create archive01a com.core.clob.applications.ArchiveAnnouncer @/bus @/archiveManager ARCHIVE01 1

# Snapshot coordinator
source -s snapshot.cmd

# Snapshot index (tracks valid snapshots from event stream)
create /snapshotIndex com.core.platform.applications.snapshot.SnapshotIndex @/bus

# Reference Data Publisher
create ref01a com.core.platform.applications.refdata.ReferenceDataPublisher @/bus REF01 equityDefinition
ref01a/setPath clob/src/main/resources
ref01a/start
ref01a/loadFiles

# Printer
create print01a com.core.clob.applications.printer.ClobPrinter @/bus .

# Injector
create inject01a com.core.clob.applications.utilities.ClobInjector @/bus INJ01
inject01a/start

seq01a/start
