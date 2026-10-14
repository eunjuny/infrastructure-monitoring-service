package dev.infrastructure.monitoring.resource;

public final class ResourceRequestException extends RuntimeException {
    public enum Reason { INVALID_QUERY, RESOURCE_NOT_FOUND }

    private final Reason reason;
    private final String field;

    private ResourceRequestException(Reason reason, String field, String message) {
        super(message);
        this.reason = reason;
        this.field = field;
    }

    public static ResourceRequestException invalid(String field, String message) {
        return new ResourceRequestException(Reason.INVALID_QUERY, field, message);
    }

    public static ResourceRequestException notFound() {
        return new ResourceRequestException(Reason.RESOURCE_NOT_FOUND, null, "리소스를 찾을 수 없습니다.");
    }

    public Reason reason() { return reason; }
    public String field() { return field; }
}
