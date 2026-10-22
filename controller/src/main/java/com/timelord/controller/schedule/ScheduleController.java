package com.timelord.controller.schedule;

import com.timelord.controller.agent.ScheduleChangeNotifier;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleService service;
    private final ScheduleChangeNotifier scheduleChanges;

    public ScheduleController(ScheduleService service, ScheduleChangeNotifier scheduleChanges) {
        this.service = service;
        this.scheduleChanges = scheduleChanges;
    }

    @GetMapping
    public List<ScheduleDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ScheduleDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleDto create(@Valid @RequestBody ScheduleRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public ScheduleDto update(@PathVariable UUID id, @Valid @RequestBody ScheduleRequest request) {
        ScheduleDto dto = service.update(id, request);
        scheduleChanges.allChanged();
        return dto;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
        scheduleChanges.allChanged();
    }

    @PostMapping("/{id}/overrides")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleDto addOverride(@PathVariable UUID id, @Valid @RequestBody OverrideRequest request) {
        ScheduleDto dto = service.addOverride(id, request);
        scheduleChanges.allChanged();
        return dto;
    }

    @DeleteMapping("/{id}/overrides/{overrideId}")
    public ScheduleDto removeOverride(@PathVariable UUID id, @PathVariable UUID overrideId) {
        ScheduleDto dto = service.removeOverride(id, overrideId);
        scheduleChanges.allChanged();
        return dto;
    }

    /** Body {@code {"enabled": false}} keeps the override but stops it adding time; {@code true} turns it back on. */
    @PutMapping("/{id}/overrides/{overrideId}/enabled")
    public ScheduleDto setOverrideEnabled(@PathVariable UUID id, @PathVariable UUID overrideId,
                                          @Valid @RequestBody EnabledRequest request) {
        ScheduleDto dto = service.setOverrideEnabled(id, overrideId, request.enabled());
        scheduleChanges.allChanged();
        return dto;
    }

    /** The concrete allowed ("on") windows the schedule resolves to. */
    @GetMapping("/{id}/allowed")
    public List<AllowedWindow> allowed(@PathVariable UUID id,
                                       @RequestParam(required = false) Instant from,
                                       @RequestParam(defaultValue = "14") int days) {
        return service.allowed(id, from != null ? from : Instant.now(), Math.min(Math.max(days, 1), 366));
    }

    /** The concrete off windows (what the agent enforces) the schedule resolves to. */
    @GetMapping("/{id}/windows")
    public List<OffWindow> windows(@PathVariable UUID id,
                                   @RequestParam(required = false) Instant from,
                                   @RequestParam(defaultValue = "14") int days) {
        return service.preview(id, from != null ? from : Instant.now(), Math.min(Math.max(days, 1), 366));
    }
}
