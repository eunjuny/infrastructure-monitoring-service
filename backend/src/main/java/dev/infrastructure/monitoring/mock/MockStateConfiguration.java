package dev.infrastructure.monitoring.mock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "monitoring.mock.enabled", havingValue = "true")
public class MockStateConfiguration {
    @Bean
    MockStateScheduler mockStateScheduler(MockStateService service,
                                          @Value("${monitoring.mock.interval-ms:5000}") long interval,
                                          @Value("${monitoring.mock.initial-delay-ms:10000}") long initialDelay) {
        return new MockStateScheduler(service, interval, initialDelay);
    }
}
