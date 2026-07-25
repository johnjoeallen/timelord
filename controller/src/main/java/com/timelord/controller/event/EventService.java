package com.timelord.controller.event;

import com.timelord.controller.agent.EventItem;
import com.timelord.controller.agent.EventResult;
import com.timelord.controller.agent.EventSubmissionRequest;
import com.timelord.controller.agent.EventSubmissionResponse;
import com.timelord.controller.common.ApiException;
import com.timelord.controller.device.Device;
import com.timelord.controller.device.DeviceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class EventService {

    private final DeviceEventRepository eventRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceEventWriter writer;

    private static final List<EventType> ERROR_LIKE_TYPES = List.of(
            EventType.AGENT_ERROR, EventType.REGISTRATION_FAILED, EventType.CONTROLLER_DISCOVERY_FAILED,
            EventType.POWER_ACTION_FAILED, EventType.POLICY_VIOLATION);

    public EventService(DeviceEventRepository eventRepository, DeviceRepository deviceRepository, DeviceEventWriter writer) {
        this.eventRepository = eventRepository;
        this.deviceRepository = deviceRepository;
        this.writer = writer;
    }

    public EventSubmissionResponse submit(UUID deviceId, EventSubmissionRequest request, String sourceIp) {
        if (!deviceRepository.existsById(deviceId)) {
            throw ApiException.notFound("DEVICE_NOT_FOUND", "No device registered with id " + deviceId);
        }

        List<EventResult> results = new ArrayList<>(request.events().size());
        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        for (EventItem item : request.events()) {
            EventResult result = writer.insertIfAbsent(deviceId, item, sourceIp);
            results.add(result);
            switch (result.status()) {
                case ACCEPTED -> accepted++;
                case DUPLICATE -> duplicates++;
                case REJECTED -> rejected++;
            }
        }
        return new EventSubmissionResponse(accepted, duplicates, rejected, results);
    }

    public DeviceEvent requireEvent(UUID eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("EVENT_NOT_FOUND", "No event with id " + eventId));
    }

    public Page<DeviceEvent> search(UUID deviceId, EventType eventType, EventSeverity severity,
                                     Instant from, Instant to, Pageable pageable) {
        return eventRepository.findAll(DeviceEventSpecifications.matching(deviceId, eventType, severity, from, to), pageable);
    }

    public Page<DeviceEvent> forDevice(UUID deviceId, Pageable pageable) {
        return eventRepository.findByDeviceId(deviceId, pageable);
    }

    public String deviceNameOrUnknown(UUID deviceId, Map<UUID, Device> cache) {
        Device device = cache.computeIfAbsent(deviceId, id -> deviceRepository.findById(id).orElse(null));
        return device != null ? device.getDeviceName() : "(unknown device)";
    }

    public long countSince(Instant since) {
        return eventRepository.countByOccurredAtAfter(since);
    }

    public long countErrorsSince(Instant since) {
        return eventRepository.countByEventTypeInAndOccurredAtAfter(ERROR_LIKE_TYPES, since);
    }
}
