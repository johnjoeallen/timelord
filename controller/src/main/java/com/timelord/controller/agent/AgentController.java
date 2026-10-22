package com.timelord.controller.agent;

import com.timelord.controller.device.DeviceService;
import com.timelord.controller.event.EventService;
import com.timelord.controller.message.MessageChangeNotifier;
import com.timelord.controller.message.MessageDto;
import com.timelord.controller.message.MessageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.async.DeferredResult;
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

    private static final int MAX_WAIT_SECONDS = 60;

    private final DeviceService deviceService;
    private final EventService eventService;
    private final ScheduleChangeNotifier scheduleChanges;
    private final MessageService messageService;
    private final MessageChangeNotifier messageChanges;

    public AgentController(DeviceService deviceService, EventService eventService,
                           ScheduleChangeNotifier scheduleChanges, MessageService messageService,
                           MessageChangeNotifier messageChanges) {
        this.messageService = messageService;
        this.messageChanges = messageChanges;
        this.deviceService = deviceService;
        this.eventService = eventService;
        this.scheduleChanges = scheduleChanges;
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

    /**
     * Long-poll for schedule changes. Returns immediately if the agent's {@code revision} is stale,
     * otherwise parks up to {@code waitSeconds} and returns as soon as something changes (or the
     * current schedule on timeout). 204 means the device has no schedule.
     */
    @GetMapping("/{deviceId}/schedule")
    public DeferredResult<ResponseEntity<AgentSchedule>> schedule(@PathVariable UUID deviceId,
                                                                  @RequestParam(required = false) String revision,
                                                                  @RequestParam(defaultValue = "0") int waitSeconds) {
        long waitMillis = Duration.ofSeconds(Math.min(Math.max(waitSeconds, 0), MAX_WAIT_SECONDS)).toMillis();
        DeferredResult<ResponseEntity<AgentSchedule>> result = new DeferredResult<>(waitMillis);

        AgentSchedule current = deviceService.scheduleFor(deviceService.requireDevice(deviceId));
        if (waitMillis == 0 || isStale(current, revision)) {
            result.setResult(toResponse(current));
            return result;
        }

        Runnable cancel = scheduleChanges.await(deviceId, () -> {
            try {
                result.setResult(toResponse(deviceService.scheduleFor(deviceService.requireDevice(deviceId))));
            } catch (RuntimeException e) {
                result.setErrorResult(e);
            }
        });
        result.onTimeout(() -> {
            cancel.run();
            result.setResult(toResponse(deviceService.scheduleFor(deviceService.requireDevice(deviceId))));
        });
        result.onCompletion(cancel);
        return result;
    }

    /**
     * Long-poll for messages queued for this device after cursor {@code after} (the highest {@code seq}
     * the agent has seen). Returns at once if any exist, otherwise parks up to {@code waitSeconds} and
     * returns whatever arrived (possibly an empty list).
     */
    @GetMapping("/{deviceId}/messages")
    public DeferredResult<List<MessageDto>> messages(@PathVariable UUID deviceId,
                                                     @RequestParam(defaultValue = "0") long after,
                                                     @RequestParam(defaultValue = "0") int waitSeconds) {
        long waitMillis = Duration.ofSeconds(Math.min(Math.max(waitSeconds, 0), MAX_WAIT_SECONDS)).toMillis();
        DeferredResult<List<MessageDto>> result = new DeferredResult<>(waitMillis);
        deviceService.requireDevice(deviceId);

        List<MessageDto> pending = messageService.pending(deviceId, after);
        if (waitMillis == 0 || !pending.isEmpty()) {
            result.setResult(pending);
            return result;
        }

        Runnable cancel = messageChanges.await(deviceId, () -> {
            try {
                result.setResult(messageService.pending(deviceId, after));
            } catch (RuntimeException e) {
                result.setErrorResult(e);
            }
        });
        result.onTimeout(() -> {
            cancel.run();
            result.setResult(List.of());
        });
        result.onCompletion(cancel);
        return result;
    }

    private static boolean isStale(AgentSchedule current, String revision) {
        return current == null ? revision != null : !current.revision().equals(revision);
    }

    private static ResponseEntity<AgentSchedule> toResponse(AgentSchedule schedule) {
        return schedule == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(schedule);
    }
}
