package com.timelord.controller.event;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DeviceEventRepository extends JpaRepository<DeviceEvent, UUID>, JpaSpecificationExecutor<DeviceEvent> {

    Page<DeviceEvent> findByDeviceId(UUID deviceId, Pageable pageable);

    List<DeviceEvent> findByDeviceIdAndEventTypeInOrderByOccurredAtAsc(UUID deviceId, Collection<EventType> eventTypes);

    long countByEventTypeInAndOccurredAtAfter(Collection<EventType> eventTypes, Instant since);

    long countBySeverityAndOccurredAtAfter(EventSeverity severity, Instant since);

    long countByOccurredAtAfter(Instant since);
}
