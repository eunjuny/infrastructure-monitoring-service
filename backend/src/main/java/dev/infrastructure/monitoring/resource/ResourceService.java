package dev.infrastructure.monitoring.resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import dev.infrastructure.monitoring.security.ResourceAccess;

import static dev.infrastructure.monitoring.resource.ResourceResponses.*;

@Service
@Transactional(readOnly = true)
public class ResourceService {
    private final ResourceRepository repository;
    private final ResourceCursorCodec cursors;
    private final MeterRegistry metrics;

    public ResourceService(ResourceRepository repository) {
        this(repository,new SimpleMeterRegistry());
    }
    public ResourceService(ResourceRepository repository,MeterRegistry metrics) {
        this(repository, metrics, new ResourceAccess(false));
    }
    @Autowired public ResourceService(ResourceRepository repository, MeterRegistry metrics, ResourceAccess access) {
        this.repository = repository; this.metrics=metrics;
        this.cursors = new ResourceCursorCodec(access::cursorIdentity);
    }

    public Page<Item> list(ResourceQuery query) {
        if (query.type() == null) throw ResourceRequestException.invalid("type", "목록 유형이 필요합니다.");
        var position = cursors.decode(query);
        if (query.parentId() != null) requireExists(query.type().parent(), query.parentId());
        validateScope(query);
        return page(observe(query,"list",()->repository.list(query, position)), query, ResourceRepository.Row::resource);
    }

    public Page<Item> children(ResourceType type, long id, String term,
                               String status, int limit, String cursor) {
        ResourceQuery.requirePositiveId(id);
        ResourceType child = type.child();
        if (child == null) {
            var query = ResourceQuery.list(type.name(), null, term, status, limit, cursor);
            cursors.decode(query);
            requireExists(type, id);
            return new Page<>(List.of(), null, false);
        }
        return list(ResourceQuery.list(child.name(), id, term, status, limit, cursor));
    }

    public Detail detail(ResourceType type, long id) {
        ResourceQuery.requirePositiveId(id);
        var row = repository.find(type, id).orElseThrow(ResourceRequestException::notFound);
        return new Detail(row.resource(), row.attributes(), row.path());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detailWithDiagnostics(ResourceType type, long id) {
        // The access-checked detail and its aggregates must describe the same DB snapshot.
        var base = detail(type, id);
        var attributes = new java.util.LinkedHashMap<String, Object>(base.attributes());
        attributes.putAll(repository.diagnostics(base.resource()));
        attributes.put("diagnosticSource", "MOCK");
        return new Detail(base.resource(), java.util.Collections.unmodifiableMap(attributes), base.path());
    }

    public List<java.util.Map<String, Object>> kubernetesEvents(ResourceType type, long id, int limit) {
        ResourceQuery.requirePositiveId(id);
        if (limit < 1 || limit > 20) {
            throw ResourceRequestException.invalid("limit", "Kubernetes 이벤트 조회 수는 1~20이어야 합니다.");
        }
        return repository.kubernetesEvents(type, id, limit);
    }

    public Path path(ResourceType type, long id) {
        return new Path(detail(type, id).path());
    }

    public Page<SearchResult> search(ResourceQuery query) {
        var position = cursors.decode(query);
        validateScope(query);
        return page(observe(query,"search",()->repository.search(query, position)), query,
                row -> new SearchResult(row.resource(), row.path()));
    }

    private void requireExists(ResourceType type, long id) {
        if (!repository.exists(type, id)) throw ResourceRequestException.notFound();
    }

    private List<ResourceRepository.Row> observe(ResourceQuery query,String operation,Supplier<List<ResourceRepository.Row>> action) {
        var sample=Timer.start(metrics); String type=query.type()==null?"ALL":query.type().name();
        try {
            var rows=action.get(); metrics.summary("infrastructure.query.rows","operation",operation,"type",type).record(rows.size());
            sample.stop(metrics.timer("infrastructure.query","operation",operation,"type",type,"outcome","success")); return rows;
        } catch(RuntimeException cause) {
            sample.stop(metrics.timer("infrastructure.query","operation",operation,"type",type,"outcome","failure")); throw cause;
        }
    }

    private void validateScope(ResourceQuery query) {
        if (query.clusterId() != null) requireExists(ResourceType.CLUSTER, query.clusterId());
        if (query.namespaceId() != null) {
            var namespace = repository.find(ResourceType.NAMESPACE, query.namespaceId()).orElseThrow(ResourceRequestException::notFound);
            if (query.clusterId() != null && namespace.path().get(0).id() != query.clusterId()) {
                throw ResourceRequestException.invalid("namespaceId", "선택한 Cluster에 속하지 않은 Namespace입니다.");
            }
            if (query.parentId() != null && (query.type() == ResourceType.POD && !query.parentId().equals(query.namespaceId())
                    || query.type() == ResourceType.NAMESPACE && namespace.resource().parent().id() != query.parentId())) {
                throw ResourceRequestException.invalid("parentId", "부모와 Namespace 범위가 일치하지 않습니다.");
            }
        }
        if (query.parentId() != null && query.clusterId() != null) {
            var parent = repository.find(query.type().parent(), query.parentId()).orElseThrow(ResourceRequestException::notFound);
            if (parent.path().get(0).id() != query.clusterId()) throw ResourceRequestException.invalid("parentId", "부모가 선택한 Cluster에 속하지 않습니다.");
        }
    }

    private <T> Page<T> page(List<ResourceRepository.Row> rows, ResourceQuery query,
                             Function<ResourceRepository.Row, T> mapper) {
        boolean hasMore = rows.size() > query.limit();
        var selected = rows.subList(0, Math.min(rows.size(), query.limit()));
        String next = null;
        if (hasMore) {
            var last = selected.get(selected.size() - 1).resource();
            next = cursors.encode(query, new ResourceCursorCodec.Position(last.type().depth(), last.id()));
        }
        return new Page<>(selected.stream().map(mapper).toList(), next, hasMore);
    }
}
