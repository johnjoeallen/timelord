package com.timelord.controller.schedule;

import com.timelord.controller.agent.AgentSchedule;
import com.timelord.controller.schedule.ScheduleDto.Rule;
import com.timelord.controller.common.ApiException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleService {

    /**
     * How far ahead the agent is told about off windows. Long enough that an agent that stays offline
     * for weeks still enforces; the agent also refreshes on every heartbeat and must confirm the cache
     * within 90 seconds of starting, so the response stays small (a few windows a day).
     */
    static final Duration AGENT_HORIZON = Duration.ofDays(28);

    private final ScheduleRepository schedules;
    private final ScheduleRuleRepository rules;
    private final ScheduleOverrideRepository overrides;

    public ScheduleService(ScheduleRepository schedules, ScheduleRuleRepository rules,
                           ScheduleOverrideRepository overrides) {
        this.schedules = schedules;
        this.rules = rules;
        this.overrides = overrides;
    }

    @Transactional(readOnly = true)
    public List<ScheduleDto> list() {
        return schedules.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ScheduleDto get(UUID id) {
        return toDto(require(id));
    }

    @Transactional
    public ScheduleDto create(ScheduleRequest request) {
        validate(request);
        if (schedules.existsByNameIgnoreCase(request.name())) {
            throw nameTaken(request.name());
        }
        Instant now = Instant.now();
        Schedule schedule = new Schedule(UUID.randomUUID());
        schedule.setCreatedAt(now);
        apply(schedule, request, now);
        return toDto(schedule);
    }

    /** Replaces name, timezone, action and the whole set of weekly rules; overrides are left alone. */
    @Transactional
    public ScheduleDto update(UUID id, ScheduleRequest request) {
        validate(request);
        Schedule schedule = require(id);
        if (schedules.existsByNameIgnoreCaseAndIdNot(request.name(), id)) {
            throw nameTaken(request.name());
        }
        rules.deleteByScheduleId(id);
        rules.flush();
        apply(schedule, request, Instant.now());
        return toDto(schedule);
    }

    @Transactional
    public void delete(UUID id) {
        schedules.delete(require(id));
    }

    @Transactional
    public ScheduleDto addOverride(UUID scheduleId, OverrideRequest request) {
        Schedule schedule = require(scheduleId);
        if (request.endDate().isBefore(request.startDate())) {
            throw ApiException.badRequest("INVALID_OVERRIDE", "endDate must not be before startDate");
        }
        LocalTime start = ScheduleResolver.startOrMidnight(request.startTime());
        LocalTime end = ScheduleResolver.endOrMidnight(request.endTime());
        requireValidWindow(start, end, "INVALID_OVERRIDE");
        overrides.save(new ScheduleOverride(UUID.randomUUID(), scheduleId, request.name(), request.startDate(),
                request.endDate(), start, end, Instant.now()));
        return toDto(schedule);
    }

    @Transactional
    public ScheduleDto removeOverride(UUID scheduleId, UUID overrideId) {
        Schedule schedule = require(scheduleId);
        ScheduleOverride override = overrides.findByIdAndScheduleId(overrideId, scheduleId)
                .orElseThrow(() -> ApiException.notFound("OVERRIDE_NOT_FOUND",
                        "No override " + overrideId + " on schedule " + scheduleId));
        overrides.delete(override);
        return toDto(schedule);
    }

    @Transactional
    public ScheduleDto setOverrideEnabled(UUID scheduleId, UUID overrideId, boolean enabled) {
        Schedule schedule = require(scheduleId);
        ScheduleOverride override = overrides.findByIdAndScheduleId(overrideId, scheduleId)
                .orElseThrow(() -> ApiException.notFound("OVERRIDE_NOT_FOUND",
                        "No override " + overrideId + " on schedule " + scheduleId));
        override.setEnabled(enabled);
        overrides.save(override);
        return toDto(schedule);
    }

    /** The off windows (what the agent enforces) the schedule resolves to. */
    @Transactional(readOnly = true)
    public List<OffWindow> preview(UUID scheduleId, Instant from, int days) {
        Schedule schedule = require(scheduleId);
        return resolve(schedule, from, from.plus(Duration.ofDays(days)));
    }

    /** The allowed ("on") windows the schedule resolves to; empty both for "no rows" and "never allowed". */
    @Transactional(readOnly = true)
    public List<AllowedWindow> allowed(UUID scheduleId, Instant from, int days) {
        Schedule schedule = require(scheduleId);
        return ScheduleResolver.allowedWindows(ZoneId.of(schedule.getTimezone()),
                rules.findByScheduleId(scheduleId), overrides.findByScheduleIdOrderByStartDateAsc(scheduleId),
                from, from.plus(Duration.ofDays(days)));
    }

    /**
     * The schedule to hand an agent, or null if the device has no schedule assigned.
     * {@code extensionUntil} (nullable) delays any off window that would start before it.
     */
    @Transactional(readOnly = true)
    public AgentSchedule scheduleFor(UUID scheduleId, Instant extensionUntil) {
        if (scheduleId == null) {
            return null;
        }
        Schedule schedule = schedules.findById(scheduleId).orElse(null);
        if (schedule == null) {
            return null;
        }
        Instant now = Instant.now();
        // The horizon ends on a UTC day boundary so the revision does not change just because time passed.
        Instant horizon = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS).plus(AGENT_HORIZON);
        List<OffWindow> windows = applyExtension(resolve(schedule, now, horizon), extensionUntil);
        return new AgentSchedule(revision(schedule.getAction(), windows), schedule.getAction(),
                schedule.getTimezone(), windows);
    }

    /** Effective windows from now with the extension applied; used to work out what "30 more minutes" means. */
    @Transactional(readOnly = true)
    public List<OffWindow> effectiveWindows(UUID scheduleId, Instant extensionUntil, Duration ahead) {
        Schedule schedule = require(scheduleId);
        Instant now = Instant.now();
        return applyExtension(resolve(schedule, now, now.plus(ahead)), extensionUntil);
    }

    /** Drops windows that end before the extension does and delays ones that would start before it. */
    static List<OffWindow> applyExtension(List<OffWindow> windows, Instant extensionUntil) {
        if (extensionUntil == null) {
            return windows;
        }
        return windows.stream()
                .filter(w -> w.onAt().isAfter(extensionUntil))
                .map(w -> w.offAt().isBefore(extensionUntil) ? new OffWindow(extensionUntil, w.onAt()) : w)
                .toList();
    }

    private static String revision(ScheduleAction action, List<OffWindow> windows) {
        String canonical = action + windows.toString();
        return UUID.nameUUIDFromBytes(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    public Schedule require(UUID id) {
        return schedules.findById(id)
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND", "No schedule with id " + id));
    }

    private List<OffWindow> resolve(Schedule schedule, Instant from, Instant to) {
        return ScheduleResolver.offWindows(ZoneId.of(schedule.getTimezone()),
                rules.findByScheduleId(schedule.getId()),
                overrides.findByScheduleIdOrderByStartDateAsc(schedule.getId()), from, to);
    }

    private void apply(Schedule schedule, ScheduleRequest request, Instant now) {
        schedule.setName(request.name().strip());
        schedule.setTimezone(request.timezone());
        schedule.setAction(request.action());
        schedule.setUpdatedAt(now);
        schedules.save(schedule);
        for (Rule rule : request.rules()) {
            rules.save(new ScheduleRule(UUID.randomUUID(), schedule.getId(), rule.days(),
                    ScheduleResolver.startOrMidnight(rule.startTime()), ScheduleResolver.endOrMidnight(rule.endTime()),
                    rule.enabled()));
        }
    }

    private ScheduleDto toDto(Schedule schedule) {
        return ScheduleDto.from(schedule, rules.findByScheduleId(schedule.getId()),
                overrides.findByScheduleIdOrderByStartDateAsc(schedule.getId()));
    }

    private void validate(ScheduleRequest request) {
        try {
            ZoneId.of(request.timezone());
        } catch (DateTimeException e) {
            throw ApiException.badRequest("INVALID_TIMEZONE", "Unknown timezone: " + request.timezone());
        }
        for (Rule rule : request.rules()) {
            if (rule.days() == null || rule.days().isEmpty()) {
                throw ApiException.badRequest("INVALID_RULE", "Each row needs at least one day");
            }
            requireValidWindow(ScheduleResolver.startOrMidnight(rule.startTime()),
                    ScheduleResolver.endOrMidnight(rule.endTime()), "INVALID_RULE");
        }
    }

    private static void requireValidWindow(java.time.LocalTime start, java.time.LocalTime end, String code) {
        if (!ScheduleResolver.isValidWindow(start, end)) {
            throw ApiException.badRequest(code, "The off time (" + end + ") must be after the on time (" + start
                    + "); use 00:00 for midnight. A window can't run past midnight: add a row for the next day instead.");
        }
    }

    private static ApiException nameTaken(String name) {
        return new ApiException(org.springframework.http.HttpStatus.CONFLICT, "SCHEDULE_NAME_TAKEN",
                "A schedule named '" + name + "' already exists");
    }
}
