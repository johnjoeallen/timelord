package com.timelord.controller.dashboard;

import com.timelord.controller.device.Device;
import com.timelord.controller.device.DeviceDetail;
import com.timelord.controller.device.DeviceService;
import com.timelord.controller.device.DeviceSessionService;
import com.timelord.controller.device.DeviceStatus;
import com.timelord.controller.device.DeviceSummary;
import com.timelord.controller.event.EventDto;
import com.timelord.controller.event.EventSeverity;
import com.timelord.controller.event.EventService;
import com.timelord.controller.event.EventType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DashboardController {

    private static final int RECENT_SESSIONS_LIMIT = 50;

    private final DeviceService deviceService;
    private final EventService eventService;
    private final DeviceSessionService sessionService;

    public DashboardController(DeviceService deviceService, EventService eventService, DeviceSessionService sessionService) {
        this.deviceService = deviceService;
        this.eventService = eventService;
        this.sessionService = sessionService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<Device> devices = deviceService.list(null, null, PageRequest.of(0, 200, Sort.by(Sort.Direction.ASC, "deviceName"))).getContent();
        model.addAttribute("devices", devices.stream().map(DeviceSummary::from).toList());
        model.addAttribute("recentSessions", sessionService.recentSessions(devices, RECENT_SESSIONS_LIMIT));
        return "dashboard";
    }

    @GetMapping("/devices")
    public String devices(@RequestParam(required = false) Boolean online,
                           @RequestParam(required = false) String hostname,
                           Model model) {
        DeviceStatus status = online == null ? null : (online ? DeviceStatus.ONLINE : DeviceStatus.OFFLINE);
        Pageable pageable = PageRequest.of(0, 100, Sort.by(Sort.Direction.ASC, "deviceName"));
        model.addAttribute("devices", deviceService.list(status, hostname, pageable).map(DeviceSummary::from).getContent());
        model.addAttribute("online", online);
        model.addAttribute("hostname", hostname);
        return "devices";
    }

    @GetMapping("/devices/{deviceId}")
    public String deviceDetail(@PathVariable UUID deviceId, Model model) {
        Device device = deviceService.requireDevice(deviceId);
        model.addAttribute("device", DeviceDetail.from(device));
        model.addAttribute("sessions", sessionService.sessionsFor(device));
        model.addAttribute("events", toDtos(eventService.forDevice(deviceId,
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "occurredAt"))).getContent()));
        return "device-detail";
    }

    @GetMapping("/events")
    public String events(@RequestParam(required = false) UUID deviceId,
                          @RequestParam(required = false) EventType eventType,
                          @RequestParam(required = false) EventSeverity severity,
                          Model model) {
        Pageable pageable = PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "occurredAt"));
        model.addAttribute("events", toDtos(eventService.search(deviceId, eventType, severity, null, null, pageable).getContent()));
        model.addAttribute("deviceId", deviceId);
        model.addAttribute("eventType", eventType);
        model.addAttribute("severity", severity);
        model.addAttribute("eventTypes", EventType.values());
        model.addAttribute("severities", EventSeverity.values());
        return "events";
    }

    private java.util.List<EventDto> toDtos(java.util.List<com.timelord.controller.event.DeviceEvent> events) {
        Map<UUID, Device> cache = new HashMap<>();
        return events.stream()
                .map(e -> EventDto.from(e, eventService.deviceNameOrUnknown(e.getDeviceId(), cache)))
                .toList();
    }
}
