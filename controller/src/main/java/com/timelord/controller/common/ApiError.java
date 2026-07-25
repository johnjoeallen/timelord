package com.timelord.controller.common;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        List<String> details,
        String correlationId
) {
    public static ApiError of(int status, String code, String message, List<String> details, String correlationId) {
        return new ApiError(Instant.now(), status, code, message, details, correlationId);
    }
}
