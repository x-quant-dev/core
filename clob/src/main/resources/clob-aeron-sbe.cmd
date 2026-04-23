#
# usage: clob-aeron-sbe.cmd <aeron_dir>
# description: clob demo using Aeron transport with SBE schema encoding
#              uses SbeSchema instead of ClobSchema for message encoding
#

source aeron-network.cmd $1
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7001

# SBE schema (drop-in replacement for ClobSchema)
create /bus/schema com.core.platform.schema.sbe.SbeSchema

# Aeron bus client
create /bus com.core.platform.bus.aeron.AeronBusClient \
    client @/bus/schema $aeron_dir $event_channel $event_stream $command_channel $command_stream $session_name

# Aeron bus server
create /busServer com.core.platform.bus.aeron.AeronBusServer \
    @/bus/schema $aeron_dir $event_channel $event_stream $command_channel $command_stream $session_name

# Sequencer
create seq01a com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01

# Printer (uses generic Decoder interface — works with any schema)
# Note: ClobCommandHandlers uses typed ClobDispatcher, so it won't work with SbeSchema
# The sequencer validates app seq nums which is schema-independent

seq01a/start
