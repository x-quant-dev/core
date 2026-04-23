#
# usage: kv-store-backup.cmd
# description: backup KV store - replays events, ready for promotion
#

source network-local.cmd
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7002

create /bus/schema com.core.kv.schema.KvSchema
create /bus com.core.platform.bus.mold.MoldBusClient \
    client @/bus/schema $event_channel $command_channel $discovery_channel

# Backup Sequencer
create /busServer/store com.core.platform.bus.mold.BufferChannelMessageStore
create /busServer com.core.platform.bus.mold.MoldBusServer \
    server @/bus @/bus/schema @/busServer/store $event_channel $command_channel $discovery_channel

create seq01b com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
create seq01b/kvHandlers com.core.clob.applications.sequencer.KvCommandHandlers @/busServer

# KV Client (receives same events, builds same state)
create kvStore com.core.clob.applications.KvStoreClient @/bus

# HA
source -s promotion-guard.cmd
source -s consensus.cmd
source -s consensus-wire.cmd seq01b

# Backup does NOT call createSession or seq/start
# It just listens on the bus
/bus/start
