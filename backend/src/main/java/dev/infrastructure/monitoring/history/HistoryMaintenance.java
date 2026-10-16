package dev.infrastructure.monitoring.history;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.LoggerFactory;

@Configuration
@EnableScheduling
public class HistoryMaintenance {
    private final ResourceHistoryRepository repository;
    private final int hours,maximum,batch;
    public HistoryMaintenance(ResourceHistoryRepository repository,@Value("${monitoring.history.retention-hours:24}") int hours,
            @Value("${monitoring.history.max-events:20000}") int maximum,@Value("${monitoring.history.cleanup-batch:5000}") int batch) {
        if(hours<1 || hours>720 || maximum<1000 || maximum>1000000 || batch<100 || batch>10000) throw new IllegalArgumentException("Invalid history retention");
        this.repository=repository; this.hours=hours; this.maximum=maximum; this.batch=batch;
    }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void cleanup() {
        try { repository.cleanup(hours,maximum,batch); }
        catch(RuntimeException cause) { LoggerFactory.getLogger(HistoryMaintenance.class).warn("History cleanup failed ({})",cause.getClass().getSimpleName()); }
    }
}
