#
# usage: aeron-archive-standby.cmd <local_archive_dir>
# description: configures an Aeron Archive replicator for the standby node
# prerequisite: aeron-network.cmd must be sourced first, primary_control_channel must be set
#

set standby_archive_dir $1
set standby_control_channel aeron:udp?endpoint=0.0.0.0:8020
set standby_control_stream 100

create /archiveReplicator com.core.platform.bus.aeron.ArchiveReplicator \
    @/vm/aeron/driver $standby_archive_dir $standby_control_channel $standby_control_stream \
    $primary_control_channel $primary_control_stream $event_channel $event_stream
