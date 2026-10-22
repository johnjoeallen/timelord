package com.timelord.controller.device;

import com.timelord.controller.agent.ScheduleChangeNotifier;
import com.timelord.controller.common.ApiException;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final EventService eventService;

    private final ScheduleChangeNotifier scheduleChanges;

    public DeviceController(DeviceService deviceService, EventService eventService,
                            ScheduleChangeNotifier scheduleChanges) {
        this.deviceService = deviceService;
        this.eventService = eventService;
        this.scheduleChanges = scheduleChanges;
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

    /** Body {@code {"calendarId": "<uuid>"}} assigns a calendar; {@code {"calendarId": null}} clears it. */
    @PutMapping("/{deviceId}/calendar")
    public DeviceDetail assignCalendar(@PathVariable UUID deviceId, @RequestBody CalendarAssignment assignment) {
        DeviceDetail detail = DeviceDetail.from(deviceService.assignCalendar(deviceId, assignment.calendarId()));
        scheduleChanges.deviceChanged(deviceId);
        return detail;
    }

    /**
     * Lets the device stay on {@code minutes} longer than its calendar allows (e.g. "another 30 mins").
     * The agent is told immediately via its schedule long-poll; the calendar is not modified.
     */
    @PostMapping("/{deviceId}/extend")
    public DeviceDetail extend(@PathVariable UUID deviceId, @RequestBody ExtendRequest request) {
        if (request.minutes() < 1 || request.minutes() > 240) {
            throw ApiException.badRequest("INVALID_EXTENSION", "minutes must be between 1 and 240");
        }
        DeviceDetail detail = DeviceDetail.from(deviceService.extend(deviceId, request.minutes()));
        scheduleChanges.deviceChanged(deviceId);
        return detail;
    }

    public record ExtendRequest(int minutes) {
    }

    public record CalendarAssignment(UUID calendarId) {
    }

    @GetMapping("/{deviceId}/events")
    public PageResponse<EventDto> events(@PathVariable UUID deviceId, Pageable pageable) {
        deviceService.requireDevice(deviceId);
        Map<UUID, Device> cache = new HashMap<>();
        return PageResponse.of(eventService.forDevice(deviceId, pageable)
                .map(e -> EventDto.from(e, eventService.deviceNameOrUnknown(e.getDeviceId(), cache))));
    }
}
