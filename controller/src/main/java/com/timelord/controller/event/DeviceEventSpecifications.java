package com.timelord.controller.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds the {@code /api/v1/events} filter as a JPA {@link Specification}
 * instead of one JPQL string with {@code :param IS NULL OR ...} for every
 * field — Postgres can't infer a bind parameter's type when every call
 * leaves it NULL (driver error 42P18, "could not determine data type"), and
 * a Specification only adds a predicate for filters that are actually set.
 */
final class DeviceEventSpecifications {

    private DeviceEventSpecifications() {
    }

    static Specification<DeviceEvent> matching(UUID deviceId, EventType eventType, EventSeverity severity,
                                                 Instant from, Instant to) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (deviceId != null) {
                predicates.add(cb.equal(root.get("deviceId"), deviceId));
            }
            if (eventType != null) {
                predicates.add(cb.equal(root.get("eventType"), eventType));
            }
            if (severity != null) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
