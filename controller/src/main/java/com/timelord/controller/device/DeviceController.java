package com.timelord.controller.device;

import com.timelord.controller.common.PageResponse;
import com.timelord.controller.event.DeviceEvent;
import com.timelord.controller.event.EventDto;
import com.timelord.controller.event.EventService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final EventService eventService;

    public DeviceController(DeviceService deviceService, EventService eventService) {
        this.deviceService = deviceService;
        this.eventService = eventService;
    }

    @GetMapping
    public PageResponse<DeviceSummary> list(@RequestParam(required = false) Boolean online,
                                             @RequestParam(required = false) String hostname,
                                             Pageable pageable) {
        DeviceStatus status = online == null ? null : (online ? DeviceStatus.ONLINE : DeviceStatus.OFFLINE);
        return PageResponse.of(deviceService.list(status, hostname, pageable).map(DeviceSummary::from));
    }

    @GetMapping("/{deviceId}")
    public DeviceDetail get(@PathVariable UUID deviceId) {
        return DeviceDetail.from(deviceService.requireDevice(deviceId));
    }

    @GetMapping("/{deviceId}/events")
    public PageResponse<EventDto> events(@PathVariable UUID deviceId, Pageable pageable) {
        deviceService.requireDevice(deviceId);
        Map<UUID, Device> cache = new HashMap<>();
        return PageResponse.of(eventService.forDevice(deviceId, pageable)
                .map(e -> EventDto.from(e, eventService.deviceNameOrUnknown(e.getDeviceId(), cache))));
    }
}
