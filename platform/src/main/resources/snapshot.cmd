#
# usage: snapshot.cmd
# description: configures the snapshot coordinator for coordinated snapshots
# prerequisite: bus client must be created first
#

create /snapshotCoordinator com.core.platform.applications.snapshot.SnapshotCoordinator @/bus SNAPSHOT01
set /snapshotCoordinator/intervalNanos 300000000000
set /snapshotCoordinator/timeoutNanos 30000000000
