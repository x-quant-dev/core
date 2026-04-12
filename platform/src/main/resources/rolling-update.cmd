#
# usage: rolling-update.cmd
# description: performs pre-promotion validation checks for rolling updates
#              source this on the STANDBY node before promoting
#              this is informational only - does not perform the promotion
#

# Bus client status (event reception)
/bus/status

# Promotion guard sync check (if loaded)
# /promotionGuard/syncStatus

# Snapshot index (if loaded)
# /snapshotIndex/status

# Archive replication (if loaded)
# /archiveReplicator/status

# Health status (if loaded)
# /health/status

# Consensus lease status (if loaded)
# /consensus/status
