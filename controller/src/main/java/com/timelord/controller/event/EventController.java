package com.timelord.controller.event;

import com.timelord.controller.common.PageResponse;
import com.timelord.controller.device.Device;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public PageResponse<EventDto> list(@RequestParam(required = false) UUID deviceId,
                                        @RequestParam(required = false) EventType eventType,
                                        @RequestParam(required = false) EventSeverity severity,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                        Pageable pageable) {
        Map<UUID, Device> cache = new HashMap<>();
        return PageResponse.of(eventService.search(deviceId, eventType, severity, from, to, pageable)
                .map(e -> EventDto.from(e, eventService.deviceNameOrUnknown(e.getDeviceId(), cache))));
    }

    @GetMapping("/{eventId}")
    public EventDto get(@PathVariable UUID eventId) {
        DeviceEvent event = eventService.requireEvent(eventId);
        Map<UUID, Device> cache = new HashMap<>();
        return EventDto.from(event, eventService.deviceNameOrUnknown(event.getDeviceId(), cache));
    }
}
