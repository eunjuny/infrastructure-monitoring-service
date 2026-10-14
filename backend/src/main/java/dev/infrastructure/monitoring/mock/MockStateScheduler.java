package dev.infrastructure.monitoring.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

public class MockStateScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(MockStateScheduler.class);
    private final MockStateService service;

    public MockStateScheduler(MockStateService service, long interval, long initialDelay) {
        if (interval < 1000 || interval > 60000 || initialDelay < 0 || initialDelay > 300000) {
            throw new IllegalArgumentException("Mock interval must be 1–60 seconds; initial delay 0–300 seconds");
        }
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${monitoring.mock.interval-ms:5000}",
               initialDelayString = "${monitoring.mock.initial-delay-ms:10000}")
    public void tick() {
        try {
            service.advance();
        } catch (RuntimeException cause) {
            // Do not log SQL, connection strings or exception messages with internal details.
            LOG.warn("Mock update failed; retrying next tick ({})", cause.getClass().getSimpleName());
        }
    }
}
