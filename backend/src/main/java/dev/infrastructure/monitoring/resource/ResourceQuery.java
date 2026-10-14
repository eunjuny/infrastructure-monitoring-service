package dev.infrastructure.monitoring.resource;

public record ResourceQuery(ResourceType type, Long parentId, String query,
                            ResourceStatus status, int limit, String cursor) {
    public static final int MAX_LIMIT = 100;
    public static final int MAX_QUERY_LENGTH = 160;
    public static final int MAX_CURSOR_LENGTH = 1200;

    public ResourceQuery {
        query = query == null ? "" : query.strip();
        cursor = cursor == null ? "" : cursor.strip();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ResourceRequestException.invalid("limit", "limit은 1부터 100 사이여야 합니다.");
        }
        if (parentId != null && parentId <= 0) {
            throw ResourceRequestException.invalid("parentId", "parentId는 양수여야 합니다.");
        }
        if (parentId != null && (type == null || type == ResourceType.CLUSTER)) {
            throw ResourceRequestException.invalid("parentId", "이 조회에서는 parentId를 사용할 수 없습니다.");
        }
        if (query.length() > MAX_QUERY_LENGTH || query.chars().anyMatch(Character::isISOControl)) {
            throw ResourceRequestException.invalid("q", "검색어는 제어 문자 없이 160자 이하여야 합니다.");
        }
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw ResourceRequestException.invalid("cursor", "커서가 너무 깁니다.");
        }
    }

    public static ResourceQuery list(String type, Long parentId, String query,
                                     String status, int limit, String cursor) {
        return new ResourceQuery(ResourceType.parse(type), parentId, query,
                ResourceStatus.parseOptional(status), limit, cursor);
    }

    public static ResourceQuery search(String type, String query, String status,
                                       int limit, String cursor) {
        var result = new ResourceQuery(type == null ? null : ResourceType.parse(type),
                null, query, ResourceStatus.parseOptional(status), limit, cursor);
        if (result.query().isEmpty()) {
            throw ResourceRequestException.invalid("q", "전체 검색에는 검색어가 필요합니다.");
        }
        return result;
    }

    public String likePattern() {
        return "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public static void requirePositiveId(long id) {
        if (id <= 0) throw ResourceRequestException.invalid("id", "리소스 ID는 양수여야 합니다.");
    }
}
