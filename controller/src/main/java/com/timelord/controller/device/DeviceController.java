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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    /** Deletes the device and its event/heartbeat history. */
    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID deviceId) {
        deviceService.delete(deviceId);
        scheduleChanges.deviceChanged(deviceId);
    }

    /** Body {@code {"scheduleId": "<uuid>"}} assigns a schedule; {@code {"scheduleId": null}} clears it. */
    @PutMapping("/{deviceId}/schedule")
    public DeviceDetail assignSchedule(@PathVariable UUID deviceId, @RequestBody ScheduleAssignment assignment) {
        DeviceDetail detail = DeviceDetail.from(deviceService.assignSchedule(deviceId, assignment.scheduleId()));
        scheduleChanges.deviceChanged(deviceId);
        return detail;
    }

    /**
     * Lets the device stay on {@code minutes} longer than its schedule allows (e.g. "another 30 mins").
     * The agent is told immediately via its schedule long-poll; the schedule is not modified.
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

    /**
     * Power override. {@code {"mode":"ON"}} follows the schedule (and cancels any override);
     * {@code {"mode":"OFF","graceMinutes":15}} is off no matter what from 15 minutes from now (default 0,
     * immediately); {@code {"mode":"ON","minutes":240,"then":"OFF"}} ignores the schedule for 240 minutes
     * and then is off ({@code then} defaults to {@code SCHEDULE}, back to following the schedule).
     */
    @PutMapping("/{deviceId}/power")
    public DeviceDetail power(@PathVariable UUID deviceId, @RequestBody PowerRequest request) {
        if (request.mode() == null) {
            throw ApiException.badRequest("INVALID_POWER", "mode must be ON or OFF");
        }
        Device device;
        if (request.mode() == PowerMode.OFF) {
            if (request.minutes() != null || request.then() != null) {
                throw ApiException.badRequest("INVALID_POWER", "minutes and then only apply to mode ON");
            }
            device = deviceService.powerOff(deviceId, request.graceMinutes() == null ? 0 : request.graceMinutes());
        } else {
            if (request.graceMinutes() != null) {
                throw ApiException.badRequest("INVALID_POWER", "graceMinutes only applies to mode OFF");
            }
            if (request.minutes() == null) {
                if (request.then() != null) {
                    throw ApiException.badRequest("INVALID_POWER", "then needs minutes");
                }
                device = deviceService.powerOn(deviceId);
            } else {
                device = deviceService.holdOn(deviceId, request.minutes(), request.then() == Then.OFF);
            }
        }
        scheduleChanges.deviceChanged(deviceId);
        return DeviceDetail.from(device);
    }

    public enum Then {
        SCHEDULE,
        OFF
    }

    public record PowerRequest(PowerMode mode, Integer minutes, Then then, Integer graceMinutes) {
    }

    public record ExtendRequest(int minutes) {
    }

    public record ScheduleAssignment(UUID scheduleId) {
    }

    @GetMapping("/{deviceId}/events")
    public PageResponse<EventDto> events(@PathVariable UUID deviceId, Pageable pageable) {
        deviceService.requireDevice(deviceId);
        Map<UUID, Device> cache = new HashMap<>();
        return PageResponse.of(eventService.forDevice(deviceId, pageable)
                .map(e -> EventDto.from(e, eventService.deviceNameOrUnknown(e.getDeviceId(), cache))));
    }
}
