package com.timelord.controller.agent;

import com.timelord.controller.device.DeviceService;
import com.timelord.controller.event.EventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent-facing API. Deliberately separate from the admin-facing
 * {@code /api/v1/devices} and {@code /api/v1/events} endpoints (design brief
 * section 3): different auth story lands here later (signed agent
 * requests), while the admin side gets operator auth.
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private final DeviceService deviceService;
    private final EventService eventService;

    public AgentController(DeviceService deviceService, EventService eventService) {
        this.deviceService = deviceService;
        this.eventService = eventService;
    }

    @PostMapping("/register")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        return deviceService.register(request, httpRequest.getRemoteAddr());
    }

    @PostMapping("/{deviceId}/heartbeat")
    public HeartbeatResponse heartbeat(@PathVariable UUID deviceId,
                                        @Valid @RequestBody HeartbeatRequest request,
                                        HttpServletRequest httpRequest) {
        return deviceService.heartbeat(deviceId, request, httpRequest.getRemoteAddr());
    }

    @PostMapping("/{deviceId}/events")
    public EventSubmissionResponse events(@PathVariable UUID deviceId,
                                           @Valid @RequestBody EventSubmissionRequest request,
                                           HttpServletRequest httpRequest) {
        return eventService.submit(deviceId, request, httpRequest.getRemoteAddr());
    }
}
