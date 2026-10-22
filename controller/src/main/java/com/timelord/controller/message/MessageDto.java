package com.timelord.controller.message;

import java.time.Instant;
import java.util.UUID;

/** What the agent receives; {@code seq} is the cursor to resume from. */
public record MessageDto(long seq, UUID id, String title, String body, Instant createdAt, Instant expiresAt) {

    static MessageDto from(DeviceMessage m) {
        return new MessageDto(m.getSeq(), m.getId(), m.getTitle(), m.getBody(), m.getCreatedAt(), m.getExpiresAt());
    }
}
