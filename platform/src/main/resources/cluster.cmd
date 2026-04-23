#
# usage: cluster.cmd <node_id> <role>
# description: registers this node in the cluster registry
# params:
#   1: node ID (e.g., seq01a, follower01, subscriber01)
#   2: role (SEQUENCER, FOLLOWER, SUBSCRIBER, STANDBY)
#

create /cluster com.core.platform.applications.cluster.ClusterRegistry
/cluster/register $1 $2 $telnet_address
