#
# usage: credit-engine.cmd
# description: standalone credit engine with sequencer and client
#

source network-local.cmd
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7001
source -s http.cmd inet:0.0.0.0:8001

create /bus/schema com.core.credit.schema.CreditSchema
create /bus com.core.platform.bus.mold.MoldBusClient \
    client @/bus/schema $event_channel $command_channel $discovery_channel

# Sequencer
create /busServer/store com.core.platform.bus.mold.BufferChannelMessageStore
create /busServer com.core.platform.bus.mold.MoldBusServer \
    server @/bus/schema @/busServer/store $event_channel $command_channel $discovery_channel

create seq01a com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
create seq01a/creditHandlers com.core.credit.applications.sequencer.CreditCommandHandlers @/busServer

# Credit Store Client (Passive Replica)
create creditStore com.core.credit.applications.CreditStoreClient @/bus

# Injector (for shell-driven commands)
create inject01a com.core.credit.applications.utilities.CreditInjector @/bus INJ01
inject01a/start

# Lifecycle
/busServer/createSession AA
seq01a/start
