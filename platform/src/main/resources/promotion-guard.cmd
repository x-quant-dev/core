#
# usage: promotion-guard.cmd
# description: creates a promotion guard that validates backup sync before sequencer promotion
# prerequisite: bus server must be created first
#

create /promotionGuard com.core.platform.applications.sequencer.PromotionGuard @/busServer
