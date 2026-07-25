package com.timelord.controller.event;

import com.timelord.controller.config.HeartbeatProperties;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounds the device_heartbeat table's growth per HeartbeatProperties.historyRetentionDays. */
@Component
public class HeartbeatRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatRetentionScheduler.class);

    private final DeviceHeartbeatRepository heartbeatRepository;
    private final HeartbeatProperties heartbeatProperties;

    public HeartbeatRetentionScheduler(DeviceHeartbeatRepository heartbeatRepository, HeartbeatProperties heartbeatProperties) {
        this.heartbeatRepository = heartbeatRepository;
        this.heartbeatProperties = heartbeatProperties;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void purgeOldHeartbeats() {
        Instant cutoff = Instant.now().minusSeconds(heartbeatProperties.historyRetentionDays() * 86_400L);
        int deleted = heartbeatRepository.deleteByReceivedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} heartbeat record(s) older than {} day(s)", deleted, heartbeatProperties.historyRetentionDays());
        }
    }
}
