package com.core.infrastructure.concurrent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;

public class ThreadIdentityGuardTest {

    @Test
    void check_succeeds_on_same_thread() {
        // When thread checks are disabled (default), check() is a no-op
        // This test verifies no exception is thrown
        var guard = new ThreadIdentityGuard();
        guard.bind();
        guard.check(); // should not throw
    }

    @Test
    void bind_and_check_do_not_throw_when_disabled() {
        // Default state: core.threadChecks is not set
        var guard = new ThreadIdentityGuard();
        guard.bind();
        guard.check();
        // no exception means success
    }
}
