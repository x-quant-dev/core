#
# usage: json-log.cmd
# description: writes logs to System.out as structured JSON
#              alternative to sysout-log.cmd for log aggregation systems
#

create /sys/out com.core.infrastructure.io.SysoutChannel
create /vm/log/channel com.core.infrastructure.log.JsonLogSink @/sys/out
/vm/log/logSink @vm/log/channel
