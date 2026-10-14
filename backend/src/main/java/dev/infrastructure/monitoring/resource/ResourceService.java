package dev.infrastructure.monitoring.resource;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Function;

import static dev.infrastructure.monitoring.resource.ResourceResponses.*;

@Service
public class ResourceService {
    private final ResourceRepository repository;
    private final ResourceCursorCodec cursors = new ResourceCursorCodec();

    public ResourceService(ResourceRepository repository) {
        this.repository = repository;
    }

    public Page<Item> list(ResourceQuery query) {
        if (query.type() == null) throw ResourceRequestException.invalid("type", "목록 유형이 필요합니다.");
        var position = cursors.decode(query);
        if (query.parentId() != null) requireExists(query.type().parent(), query.parentId());
        return page(repository.list(query, position), query, ResourceRepository.Row::resource);
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

    public Path path(ResourceType type, long id) {
        return new Path(detail(type, id).path());
    }

    public Page<SearchResult> search(ResourceQuery query) {
        var position = cursors.decode(query);
        return page(repository.search(query, position), query,
                row -> new SearchResult(row.resource(), row.path()));
    }

    private void requireExists(ResourceType type, long id) {
        if (!repository.exists(type, id)) throw ResourceRequestException.notFound();
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
