package com.core.infrastructure.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenNullPointerException;

class NioSelectorPollerTest {

    @Test
    void addPoller_null_throwsNpe() throws IOException {
        try (var selector = new NioSelector()) {
            thenNullPointerException().isThrownBy(() -> selector.addPoller(null));
        }
    }

    @Test
    void selectNow_invokesRegisteredPoller() throws IOException {
        try (var selector = new NioSelector()) {
            var count = new AtomicInteger();
            selector.addPoller(count::incrementAndGet);

            selector.selectNow();

            then(count.get()).isEqualTo(1);
        }
    }

    @Test
    void selectNow_invokesMultiplePollers_inRegistrationOrder() throws IOException {
        try (var selector = new NioSelector()) {
            var seq = new int[]{0};
            var firstAt = new int[]{-1};
            var secondAt = new int[]{-1};
            selector.addPoller(() -> firstAt[0] = seq[0]++);
            selector.addPoller(() -> secondAt[0] = seq[0]++);

            selector.selectNow();

            then(firstAt[0]).isEqualTo(0);
            then(secondAt[0]).isEqualTo(1);
        }
    }

    @Test
    void selectNow_calledMultipleTimes_pollerInvokedEachTime() throws IOException {
        try (var selector = new NioSelector()) {
            var count = new AtomicInteger();
            selector.addPoller(count::incrementAndGet);

            selector.selectNow();
            selector.selectNow();
            selector.selectNow();

            then(count.get()).isEqualTo(3);
        }
    }

    @Test
    void selectWithTimeout_invokesRegisteredPoller() throws IOException {
        try (var selector = new NioSelector()) {
            var count = new AtomicInteger();
            selector.addPoller(count::incrementAndGet);

            selector.select(1_000_000L); // 1 ms

            then(count.get()).isEqualTo(1);
        }
    }

    @Test
    void noPollers_selectNow_doesNotThrow() throws IOException {
        try (var selector = new NioSelector()) {
            selector.selectNow();
        }
    }
}
