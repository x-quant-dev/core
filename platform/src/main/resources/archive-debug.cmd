#
# usage: archive-debug.cmd
# description: creates Archive debug and export tools for production investigation
# prerequisite: aeron driver must be available (aeron-network.cmd or aeron-embedded.cmd)
#

create /archiveExport com.core.platform.bus.aeron.ArchiveToCorefile @/vm/aeron/driver
create /archiveDebug com.core.platform.bus.aeron.ArchiveDebugTool @/vm/aeron/driver @/bus/schema
