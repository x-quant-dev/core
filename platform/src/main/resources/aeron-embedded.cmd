#
# usage: aeron-embedded.cmd <aeron_dir>
# description: creates an embedded Aeron Media Driver
#

set aeron_dir $1
create /vm/aeron/config com.core.platform.bus.aeron.AeronDriverConfiguration
/vm/aeron/config/setAeronDirectory $aeron_dir
create /vm/aeron/driver com.core.platform.bus.aeron.EmbeddedAeronDriver @/vm/aeron/config/toContext
