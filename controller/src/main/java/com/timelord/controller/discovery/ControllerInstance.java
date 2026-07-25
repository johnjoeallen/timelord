package com.timelord.controller.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Single row representing this controller's own persisted identity, so a
 * generated controller ID survives a restart even when
 * {@code TIMELORD_CONTROLLER_ID} isn't set. See {@link ControllerIdentityService}.
 */
@Entity
@Table(name = "controller_instance")
public class ControllerInstance {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "public_url", nullable = false)
    private String publicUrl;

    @Column(nullable = false)
    private int priority;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ControllerInstance() {
        // JPA
    }

    public ControllerInstance(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
