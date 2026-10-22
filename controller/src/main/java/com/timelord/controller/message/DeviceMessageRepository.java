package com.timelord.controller.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceMessageRepository extends JpaRepository<DeviceMessage, UUID> {

    @Query("""
            select m from DeviceMessage m
            where m.deviceId = :deviceId and m.seq > :after and m.expiresAt > :now
            order by m.seq asc
            """)
    List<DeviceMessage> findUndelivered(@Param("deviceId") UUID deviceId, @Param("after") long after,
                                        @Param("now") Instant now);

    List<DeviceMessage> findTop10ByDeviceIdOrderByCreatedAtDesc(UUID deviceId);
}
