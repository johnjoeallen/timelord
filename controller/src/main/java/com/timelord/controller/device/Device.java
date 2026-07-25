package com.timelord.controller.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "device")
public class Device {

    @Id
    private UUID id;

    @Column(name = "device_name", nullable = false)
    private String deviceName;

    @Column(nullable = false)
    private String hostname;

    @Column(name = "agent_version")
    private String agentVersion;

    @Column(name = "operating_system")
    private String operatingSystem;

    @Column(name = "operating_system_version")
    private String operatingSystemVersion;

    private String architecture;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "local_ip_addresses", columnDefinition = "jsonb", nullable = false)
    private List<String> localIpAddresses = new ArrayList<>();

    @Column(name = "source_ip")
    private String sourceIp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceStatus status = DeviceStatus.OFFLINE;

    @Column(name = "service_state")
    private String serviceState;

    @Column(name = "session_state")
    private String sessionState;

    @Column(name = "current_username")
    private String currentUsername;

    @Column(name = "idle_seconds")
    private Long idleSeconds;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "last_registration_at", nullable = false)
    private Instant lastRegistrationAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Device() {
        // JPA
    }

    public Device(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public void setOperatingSystem(String operatingSystem) {
        this.operatingSystem = operatingSystem;
    }

    public String getOperatingSystemVersion() {
        return operatingSystemVersion;
    }

    public void setOperatingSystemVersion(String operatingSystemVersion) {
        this.operatingSystemVersion = operatingSystemVersion;
    }

    public String getArchitecture() {
        return architecture;
    }

    public void setArchitecture(String architecture) {
        this.architecture = architecture;
    }

    public List<String> getLocalIpAddresses() {
        return localIpAddresses;
    }

    public void setLocalIpAddresses(List<String> localIpAddresses) {
        this.localIpAddresses = localIpAddresses != null ? localIpAddresses : new ArrayList<>();
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public void setSourceIp(String sourceIp) {
        this.sourceIp = sourceIp;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
    }

    public String getServiceState() {
        return serviceState;
    }

    public void setServiceState(String serviceState) {
        this.serviceState = serviceState;
    }

    public String getSessionState() {
        return sessionState;
    }

    public void setSessionState(String sessionState) {
        this.sessionState = sessionState;
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    public void setCurrentUsername(String currentUsername) {
        this.currentUsername = currentUsername;
    }

    public Long getIdleSeconds() {
        return idleSeconds;
    }

    public void setIdleSeconds(Long idleSeconds) {
        this.idleSeconds = idleSeconds;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Instant getLastRegistrationAt() {
        return lastRegistrationAt;
    }

    public void setLastRegistrationAt(Instant lastRegistrationAt) {
        this.lastRegistrationAt = lastRegistrationAt;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
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
