package com.timelord.controller.device;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically flips devices that have stopped heartbeating from ONLINE to OFFLINE. */
@Component
public class DeviceStatusScheduler {

    private final DeviceService deviceService;

    public DeviceStatusScheduler(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @Scheduled(fixedDelay = 15_000)
    public void recomputeOnlineStatus() {
        deviceService.recomputeOnlineStatus();
    }
}
