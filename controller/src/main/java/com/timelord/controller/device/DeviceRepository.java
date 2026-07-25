package com.timelord.controller.device;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Page<Device> findByStatus(DeviceStatus status, Pageable pageable);

    Page<Device> findByHostnameContainingIgnoreCase(String hostname, Pageable pageable);

    Page<Device> findByStatusAndHostnameContainingIgnoreCase(DeviceStatus status, String hostname, Pageable pageable);

    long countByStatus(DeviceStatus status);

    /**
     * Devices currently marked ONLINE whose most recent signal of life —
     * last heartbeat, or registration if no heartbeat has arrived yet — is
     * older than {@code cutoff}.
     */
    @Query("""
            select d from Device d
            where d.status = com.timelord.controller.device.DeviceStatus.ONLINE
              and coalesce(d.lastHeartbeatAt, d.lastRegistrationAt) < :cutoff
            """)
    List<Device> findStaleOnlineDevices(@Param("cutoff") Instant cutoff);
}
