package com.timelord.controller.event;

import com.timelord.controller.agent.EventItem;
import com.timelord.controller.agent.EventResult;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts one event, treating an eventId collision as a duplicate rather
 * than an error. Each call runs in its own transaction (REQUIRES_NEW) so a
 * constraint violation on one event in a batch — either an exact
 * retransmission we raced with, or (harmlessly) our own pre-check missing a
 * concurrent insert — only rolls back that single row, not the whole batch
 * or the caller's transaction. Postgres aborts a transaction on the first
 * failed statement, so this must not share a transaction with anything else.
 */
@Component
class DeviceEventWriter {

    private final DeviceEventRepository eventRepository;

    DeviceEventWriter(DeviceEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventResult insertIfAbsent(UUID deviceId, EventItem item, String sourceIp) {
        if (eventRepository.existsById(item.eventId())) {
            return EventResult.duplicate(item.eventId());
        }
        try {
            DeviceEvent event = new DeviceEvent(item.eventId());
            event.setDeviceId(deviceId);
            event.setEventType(item.eventType());
            event.setOccurredAt(item.occurredAt());
            event.setReceivedAt(Instant.now());
            event.setSeverity(item.severity());
            event.setSource(item.source());
            event.setSessionId(item.sessionId());
            event.setUsername(item.username());
            event.setSourceIp(sourceIp);
            event.setData(item.data() != null ? item.data() : Map.of());
            eventRepository.saveAndFlush(event);
            return EventResult.accepted(item.eventId());
        } catch (DataIntegrityViolationException e) {
            return EventResult.duplicate(item.eventId());
        }
    }
}
