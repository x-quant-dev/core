#
# usage: health.cmd
# description: creates health check endpoints accessible via shell (Telnet/HTTP/WS)
#
# When used with http.cmd, endpoints are accessible at:
#   GET /health/live
#   GET /health/ready
#   GET /health/leader
#   GET /health/schema
#   GET /health/status
#

create /health com.core.platform.applications.health.HealthEndpoint @/busServer
