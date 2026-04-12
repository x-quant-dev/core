#
# usage: clob-aeron.cmd <aeron_dir>
# description: demonstration of a clob inside the sequencer using Aeron transport
#

source aeron-network.cmd $1
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7001
create /bus/schema com.core.clob.schema.ClobSchema

# Aeron bus client
create /bus com.core.platform.bus.aeron.AeronBusClient \
    client @/bus/schema $aeron_dir $event_channel $event_stream $command_channel $command_stream $session_name

# Aeron bus server
create /busServer com.core.platform.bus.aeron.AeronBusServer \
    @/bus/schema $aeron_dir $event_channel $event_stream $command_channel $command_stream $session_name

# Sequencer
create seq01a com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
create seq01a/handlers com.core.clob.applications.sequencer.ClobCommandHandlers @/busServer

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
