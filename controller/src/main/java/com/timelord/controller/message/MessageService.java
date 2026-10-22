package com.timelord.controller.message;

import com.timelord.controller.common.ApiException;
import com.timelord.controller.device.Device;
import com.timelord.controller.device.DeviceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessageService {

    static final int MAX_TITLE = 100;
    static final int MAX_BODY = 500;
    static final int DEFAULT_EXPIRY_MINUTES = 240;
    static final int MAX_EXPIRY_MINUTES = 7 * 24 * 60;

    private final DeviceMessageRepository messages;
    private final DeviceRepository devices;
    private final MessageChangeNotifier notifier;

    public MessageService(DeviceMessageRepository messages, DeviceRepository devices, MessageChangeNotifier notifier) {
        this.messages = messages;
        this.devices = devices;
        this.notifier = notifier;
    }

    @Transactional
    public DeviceMessage send(UUID deviceId, MessageRequest request) {
        Device device = devices.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "No device registered with id " + deviceId));
        DeviceMessage message = queue(device.getId(), request);
        wakeAfterCommit(List.of(device.getId()));
        return message;
    }

    /** Queues the same message for every registered device; returns how many devices got it. */
    @Transactional
    public int sendToAll(MessageRequest request) {
        List<UUID> ids = devices.findAll().stream().map(Device::getId).toList();
        ids.forEach(id -> queue(id, request));
        wakeAfterCommit(ids);
        return ids.size();
    }

    /** Messages for the agent past {@code after} that haven't expired; marks them delivered. */
    @Transactional
    public List<MessageDto> pending(UUID deviceId, long after) {
        Instant now = Instant.now();
        List<DeviceMessage> found = messages.findUndelivered(deviceId, after, now);
        found.stream().filter(m -> m.getDeliveredAt() == null).forEach(m -> m.setDeliveredAt(now));
        return found.stream().map(MessageDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<DeviceMessage> recent(UUID deviceId) {
        return messages.findTop10ByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    private DeviceMessage queue(UUID deviceId, MessageRequest request) {
        String body = request.body() == null ? "" : request.body().strip();
        String title = request.title() == null || request.title().isBlank() ? null : request.title().strip();
        if (body.isEmpty()) {
            throw ApiException.badRequest("INVALID_MESSAGE", "Message text is required");
        }
        if (body.length() > MAX_BODY || (title != null && title.length() > MAX_TITLE)) {
            throw ApiException.badRequest("INVALID_MESSAGE",
                    "Message text is limited to " + MAX_BODY + " characters and the title to " + MAX_TITLE);
        }
        int minutes = request.expiresInMinutes() == null ? DEFAULT_EXPIRY_MINUTES : request.expiresInMinutes();
        if (minutes < 1 || minutes > MAX_EXPIRY_MINUTES) {
            throw ApiException.badRequest("INVALID_MESSAGE", "expiresInMinutes must be between 1 and " + MAX_EXPIRY_MINUTES);
        }
        Instant now = Instant.now();
        return messages.save(new DeviceMessage(UUID.randomUUID(), deviceId, title, body, now,
                now.plus(Duration.ofMinutes(minutes))));
    }

    /** Wake parked agent polls only once the rows are visible to the query they will run. */
    private void wakeAfterCommit(List<UUID> deviceIds) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deviceIds.forEach(notifier::deviceChanged);
                    }
                });
    }
}
