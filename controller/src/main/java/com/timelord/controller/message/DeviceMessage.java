package com.timelord.controller.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "device_message")
public class DeviceMessage {

    @Id
    private UUID id;

    /** Database-assigned cursor; only meaningful once the row has been (re)loaded. */
    @Generated(event = EventType.INSERT)
    @Column(insertable = false, updatable = false)
    private Long seq;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    private String title;

    @Column(nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected DeviceMessage() {
    }

    public DeviceMessage(UUID id, UUID deviceId, String title, String body, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.deviceId = deviceId;
        this.title = title;
        this.body = body;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public Long getSeq() {
        return seq;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }
}
