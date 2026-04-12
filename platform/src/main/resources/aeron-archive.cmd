#
# usage: aeron-archive.cmd <archive_dir>
# description: configures an embedded Aeron Archive for the primary node (LOCAL recording)
# prerequisite: aeron-network.cmd must be sourced first
#

set archive_dir $1
set archive_control_channel aeron:udp?endpoint=0.0.0.0:8010
set archive_control_stream 100

create /archiveManager com.core.platform.bus.aeron.ArchiveManager \
    @/vm/aeron/driver $archive_dir $archive_control_channel $archive_control_stream $event_channel $event_stream
