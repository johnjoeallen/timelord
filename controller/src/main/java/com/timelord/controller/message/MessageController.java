package com.timelord.controller.message;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for sending messages to devices' logged-on users. */
@RestController
@RequestMapping("/api/v1")
public class MessageController {

    private final MessageService service;

    public MessageController(MessageService service) {
        this.service = service;
    }

    @PostMapping("/devices/{deviceId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageDto send(@PathVariable UUID deviceId, @Valid @RequestBody MessageRequest request) {
        return MessageDto.from(service.send(deviceId, request));
    }

    @GetMapping("/devices/{deviceId}/messages")
    public List<MessageDto> recent(@PathVariable UUID deviceId) {
        return service.recent(deviceId).stream().map(MessageDto::from).toList();
    }

    /** Same message to every registered device. */
    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Integer> broadcast(@Valid @RequestBody MessageRequest request) {
        return Map.of("devices", service.sendToAll(request));
    }
}
