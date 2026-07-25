package com.timelord.controller.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceHeartbeatRepository extends JpaRepository<DeviceHeartbeat, UUID> {

    @Modifying
    @Query("delete from DeviceHeartbeat h where h.receivedAt < :cutoff")
    int deleteByReceivedAtBefore(@Param("cutoff") Instant cutoff);
}
