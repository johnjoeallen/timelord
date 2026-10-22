package com.timelord.controller.event;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceEventRepository extends JpaRepository<DeviceEvent, UUID>, JpaSpecificationExecutor<DeviceEvent> {

    Page<DeviceEvent> findByDeviceId(UUID deviceId, Pageable pageable);

    Page<DeviceEvent> findByDeviceIdOrderByOccurredAtAsc(UUID deviceId, Pageable pageable);

    List<DeviceEvent> findByDeviceIdAndEventTypeInOrderByOccurredAtAsc(UUID deviceId, Collection<EventType> eventTypes);

    long countByEventTypeInAndOccurredAtAfter(Collection<EventType> eventTypes, Instant since);

    long countBySeverityAndOccurredAtAfter(EventSeverity severity, Instant since);

    long countByOccurredAtAfter(Instant since);

    @Modifying
    @Query("delete from DeviceEvent e where e.deviceId = :deviceId")
    int deleteAllByDeviceId(@Param("deviceId") UUID deviceId);
}
