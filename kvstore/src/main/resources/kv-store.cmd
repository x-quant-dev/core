#
# usage: kv-store.cmd
# description: standalone key/value store with sequencer and client
#

source network-local.cmd
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7001

create /bus/schema com.core.kv.schema.KvSchema
create /bus com.core.platform.bus.mold.MoldBusClient \
    client @/bus/schema $event_channel $command_channel $discovery_channel

# Sequencer
create /busServer/store com.core.platform.bus.mold.BufferChannelMessageStore
create /busServer com.core.platform.bus.mold.MoldBusServer \
    server @/bus/schema @/busServer/store $event_channel $command_channel $discovery_channel

create seq01a com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
create seq01a/kvHandlers com.core.kv.applications.sequencer.KvCommandHandlers @/busServer

# KV Store Client
create kvStore com.core.kv.applications.KvStoreClient @/bus

# Injector (for shell-driven sends)
create inject01a com.core.platform.applications.utilities.Injector @/bus INJ01
inject01a/start

# Lifecycle
/busServer/createSession AA
seq01a/start
