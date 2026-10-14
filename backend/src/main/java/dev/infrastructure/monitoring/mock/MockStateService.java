package dev.infrastructure.monitoring.mock;

import dev.infrastructure.monitoring.resource.ResourceType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

@Service
public class MockStateService {
    public record Result(Instant changedAt, Map<ResourceType, Integer> changed) { }
    private final MockStateRepository repository;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Map<ResourceType, Long> cursors = new EnumMap<>(ResourceType.class);

    @Autowired
    public MockStateService(MockStateRepository repository, PlatformTransactionManager manager) {
        this(repository, new TransactionTemplate(manager), Clock.systemUTC());
    }

    MockStateService(MockStateRepository repository, TransactionOperations transactions, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
    }

    static int batchSize(ResourceType type) {
        return switch (type) { case CLUSTER -> 1; case NODE -> 5; case NAMESPACE -> 10; case POD -> 100; };
    }

    /** Single-process scheduler: advance cursors only after the whole tick commits. */
    public synchronized Result advance() {
        Instant changedAt = clock.instant();
        var nextCursors = new EnumMap<ResourceType, Long>(ResourceType.class);
        Result result = transactions.execute(transaction -> {
            var changed = new EnumMap<ResourceType, Integer>(ResourceType.class);
            for (ResourceType type : ResourceType.values()) {
                long after = cursors.getOrDefault(type, 0L);
                var ids = repository.nextIds(type, after, batchSize(type));
                if (ids.isEmpty() && after != 0) ids = repository.nextIds(type, 0, batchSize(type));
                int count = repository.change(type, ids, changedAt);
                if (count != ids.size()) throw new IllegalStateException("Mock batch changed unexpectedly");
                nextCursors.put(type, ids.isEmpty() ? 0 : ids.get(ids.size() - 1));
                changed.put(type, count);
            }
            return new Result(changedAt, Map.copyOf(changed));
        });
        if (result == null) throw new IllegalStateException("Mock transaction returned no result");
        cursors.putAll(nextCursors);
        return result;
    }
}
