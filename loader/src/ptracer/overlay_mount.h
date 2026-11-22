#ifndef OVERLAY_MOUNT_H
#define OVERLAY_MOUNT_H

#include <stdbool.h>

/* INFO: Lifecycle of the /system/lib{,64} overlay scaffold.
           Mount is idempotent and best-effort: a failure leaves the loader on
           its module-dir fallback path (existing ReadModules fallback) and
           boot continues. Unmount is MNT_DETACH, so mapped loader/module
           pages stay alive regardless. Consumption bookkeeping is per zygote
           arch; teardown happens when every arch that was injected has
           acked, or when the degrade timer fires. */

bool overlay_mount_all(void);

void overlay_unmount_all(void);

/* INFO: Called by the monitor on ZYGOTE{64,32}_INJECTED. Marks the arch as
           injected, resets its consumed flag, and arms the degrade timer. */
void overlay_notify_injected(bool is_64bit);

/* INFO: Called by the monitor on OVERLAY{64,32}_CONSUMED. May trigger the
           teardown inline. */
void overlay_notify_consumed(bool is_64bit);

bool overlay_all_consumed(void);

/* INFO: Creates the degrade timerfd. Returns -1 on failure (timer simply
           never fires; overlay then stays mounted - current behavior). */
int overlay_timer_create(void);

void overlay_arm_degrade_timer(void);

/* INFO: Epoll callback wrapper: drains the timerfd and unmounts regardless
           of pending acks when it expired (fail-safe degrade). */
void overlay_timer_fired(void);

#endif /* OVERLAY_MOUNT_H */
