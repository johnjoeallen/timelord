package com.timelord.controller.common;

import com.timelord.controller.config.ControllerProperties;
import com.timelord.controller.discovery.ControllerIdentityService;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    private static final int PROTOCOL_VERSION = 1;

    private final ControllerIdentityService identityService;
    private final ControllerProperties controllerProperties;

    public SystemInfoController(ControllerIdentityService identityService, ControllerProperties controllerProperties) {
        this.identityService = identityService;
        this.controllerProperties = controllerProperties;
    }

    @GetMapping("/info")
    public SystemInfoResponse info() {
        return new SystemInfoResponse(
                identityService.controllerId(),
                identityService.controllerName(),
                PROTOCOL_VERSION,
                controllerProperties.version(),
                Instant.now()
        );
    }
}
