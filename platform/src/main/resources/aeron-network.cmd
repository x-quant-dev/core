#
# usage: aeron-network.cmd <aeron_dir>
# description: sets Aeron channel defaults for local development
#

set aeron_dir $1
set event_channel aeron:udp?endpoint=239.200.0.1:40123
set command_channel aeron:udp?endpoint=239.200.0.2:40124
set event_stream 1001
set command_stream 1002
set session_name CORE01
