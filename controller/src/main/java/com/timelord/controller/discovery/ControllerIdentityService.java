package com.timelord.controller.discovery;

import com.timelord.controller.config.ControllerProperties;
import com.timelord.controller.config.DiscoveryProperties;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves and persists this controller's identity on startup: an explicit
 * {@code TIMELORD_CONTROLLER_ID} wins, otherwise a previously-generated ID is
 * reused from {@code controller_instance}, otherwise a new one is generated
 * and persisted so it survives the next restart.
 */
@Service
public class ControllerIdentityService {

    private static final Logger log = LoggerFactory.getLogger(ControllerIdentityService.class);

    private final ControllerInstanceRepository repository;
    private final ControllerProperties controllerProperties;
    private final DiscoveryProperties discoveryProperties;

    private volatile ControllerInstance identity;

    public ControllerIdentityService(ControllerInstanceRepository repository,
                                      ControllerProperties controllerProperties,
                                      DiscoveryProperties discoveryProperties) {
        this.repository = repository;
        this.controllerProperties = controllerProperties;
        this.discoveryProperties = discoveryProperties;
    }

    @PostConstruct
    public synchronized void init() {
        List<ControllerInstance> existing = repository.findAllOrderByCreatedAtAsc();

        UUID id;
        String configuredId = controllerProperties.id();
        if (configuredId != null && !configuredId.isBlank()) {
            id = UUID.fromString(configuredId);
        } else if (!existing.isEmpty()) {
            id = existing.get(0).getId();
        } else {
            id = UUID.randomUUID();
            log.info("No TIMELORD_CONTROLLER_ID configured; generated {}", id);
        }

        Instant now = Instant.now();
        ControllerInstance instance = existing.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElseGet(() -> {
                    ControllerInstance created = new ControllerInstance(id);
                    created.setCreatedAt(now);
                    return created;
                });
        instance.setName(controllerProperties.name());
        instance.setPublicUrl(controllerProperties.publicUrl());
        instance.setPriority(discoveryProperties.priority());
        instance.setUpdatedAt(now);

        this.identity = repository.save(instance);
        log.info("Controller identity: id={} name={} publicUrl={}", identity.getId(), identity.getName(), identity.getPublicUrl());
    }

    public UUID controllerId() {
        return identity.getId();
    }

    public String controllerName() {
        return identity.getName();
    }

    public String publicUrl() {
        return identity.getPublicUrl();
    }

    public int priority() {
        return identity.getPriority();
    }
}
