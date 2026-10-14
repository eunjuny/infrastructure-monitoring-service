package dev.infrastructure.monitoring.resource;

import java.util.Locale;

public enum ResourceStatus {
    NORMAL, WARNING, ERROR;

    public static ResourceStatus parseOptional(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw ResourceRequestException.invalid("status", "상태는 NORMAL, WARNING, ERROR 중 하나여야 합니다.");
        }
    }
}
