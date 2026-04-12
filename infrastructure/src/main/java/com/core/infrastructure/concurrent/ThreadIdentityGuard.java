package com.core.infrastructure.concurrent;

/**
 * A dev-only guard that validates all calls come from the same thread.
 *
 * <p>Enabled when the system property {@code core.threadChecks} is set to {@code true}.
 * When disabled (default), all methods are no-ops with zero overhead.
 *
 * <p>Usage:
 * <pre>
 * private final ThreadIdentityGuard threadGuard = new ThreadIdentityGuard();
 *
 * // In event loop setup or first operation:
 * threadGuard.bind();
 *
 * // In @Command or other methods that must run on the event loop:
 * threadGuard.check();
 * </pre>
 */
public final class ThreadIdentityGuard {

    private static final boolean ENABLED = Boolean.getBoolean("core.threadChecks");

    private Thread owner;

    /**
     * Binds this guard to the current thread.
     * Subsequent calls to {@link #check()} will verify the caller is on this thread.
     * No-op if thread checks are disabled.
     */
    public void bind() {
        if (!ENABLED) {
            return;
        }
        owner = Thread.currentThread();
    }

    /**
     * Verifies the current thread matches the bound thread.
     * If no thread has been bound yet, binds to the current thread.
     * No-op if thread checks are disabled.
     *
     * @throws IllegalStateException if called from a different thread than the bound thread
     */
    public void check() {
        if (!ENABLED) {
            return;
        }
        if (owner == null) {
            owner = Thread.currentThread();
            return;
        }
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException(
                    "cross-thread invocation detected: expected=" + owner.getName()
                    + ", actual=" + Thread.currentThread().getName());
        }
    }

    /**
     * Returns whether thread identity checks are enabled.
     *
     * @return true if the {@code core.threadChecks} system property is set to {@code true}
     */
    public static boolean isEnabled() {
        return ENABLED;
    }
}
