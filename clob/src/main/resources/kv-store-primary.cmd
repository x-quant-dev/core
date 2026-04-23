#
# usage: kv-store-primary.cmd
# description: primary KV store with HA consensus
#

source network-local.cmd
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7001

create /bus/schema com.core.kv.schema.KvSchema
create /bus com.core.platform.bus.mold.MoldBusClient \
    client @/bus/schema $event_channel $command_channel $discovery_channel

# Primary Sequencer
create /busServer/store com.core.platform.bus.mold.BufferChannelMessageStore
create /busServer com.core.platform.bus.mold.MoldBusServer \
    server @/bus @/bus/schema @/busServer/store $event_channel $command_channel $discovery_channel

create seq01a com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
create seq01a/kvHandlers com.core.clob.applications.sequencer.KvCommandHandlers @/busServer

# KV Client + Injector
create kvStore com.core.clob.applications.KvStoreClient @/bus
create inject01a com.core.platform.applications.utilities.Injector @/bus INJ01
inject01a/start

# HA: these three lines add consensus fencing
source -s promotion-guard.cmd
source -s consensus.cmd
source -s consensus-wire.cmd seq01a

# Lifecycle
/busServer/createSession AA
seq01a/start
