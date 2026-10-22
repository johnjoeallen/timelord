package com.timelord.controller.calendar;

import com.timelord.controller.agent.AgentSchedule;
import com.timelord.controller.calendar.CalendarDto.Rule;
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
public class CalendarService {

    /**
     * How far ahead the agent is told about off windows. Long enough that an agent that stays offline
     * for weeks still enforces; the agent also refreshes on every heartbeat and must confirm the cache
     * within 90 seconds of starting, so the response stays small (a few windows a day).
     */
    static final Duration AGENT_HORIZON = Duration.ofDays(28);

    private final CalendarRepository calendars;
    private final CalendarRuleRepository rules;
    private final CalendarOverrideRepository overrides;

    public CalendarService(CalendarRepository calendars, CalendarRuleRepository rules,
                           CalendarOverrideRepository overrides) {
        this.calendars = calendars;
        this.rules = rules;
        this.overrides = overrides;
    }

    @Transactional(readOnly = true)
    public List<CalendarDto> list() {
        return calendars.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public CalendarDto get(UUID id) {
        return toDto(require(id));
    }

    @Transactional
    public CalendarDto create(CalendarRequest request) {
        validate(request);
        if (calendars.existsByNameIgnoreCase(request.name())) {
            throw nameTaken(request.name());
        }
        Instant now = Instant.now();
        Calendar calendar = new Calendar(UUID.randomUUID());
        calendar.setCreatedAt(now);
        apply(calendar, request, now);
        return toDto(calendar);
    }

    /** Replaces name, timezone, action and the whole set of weekly rules; overrides are left alone. */
    @Transactional
    public CalendarDto update(UUID id, CalendarRequest request) {
        validate(request);
        Calendar calendar = require(id);
        if (calendars.existsByNameIgnoreCaseAndIdNot(request.name(), id)) {
            throw nameTaken(request.name());
        }
        rules.deleteByCalendarId(id);
        rules.flush();
        apply(calendar, request, Instant.now());
        return toDto(calendar);
    }

    @Transactional
    public void delete(UUID id) {
        calendars.delete(require(id));
    }

    @Transactional
    public CalendarDto addOverride(UUID calendarId, OverrideRequest request) {
        Calendar calendar = require(calendarId);
        if (request.endDate().isBefore(request.startDate())) {
            throw ApiException.badRequest("INVALID_OVERRIDE", "endDate must not be before startDate");
        }
        LocalTime start = CalendarResolver.startOrMidnight(request.startTime());
        LocalTime end = CalendarResolver.endOrMidnight(request.endTime());
        requireValidWindow(start, end, "INVALID_OVERRIDE");
        overrides.save(new CalendarOverride(UUID.randomUUID(), calendarId, request.name(), request.startDate(),
                request.endDate(), start, end, Instant.now()));
        return toDto(calendar);
    }

    @Transactional
    public CalendarDto removeOverride(UUID calendarId, UUID overrideId) {
        Calendar calendar = require(calendarId);
        CalendarOverride override = overrides.findByIdAndCalendarId(overrideId, calendarId)
                .orElseThrow(() -> ApiException.notFound("OVERRIDE_NOT_FOUND",
                        "No override " + overrideId + " on schedule " + calendarId));
        overrides.delete(override);
        return toDto(calendar);
    }

    /** The off windows (what the agent enforces) the calendar resolves to. */
    @Transactional(readOnly = true)
    public List<OffWindow> preview(UUID calendarId, Instant from, int days) {
        Calendar calendar = require(calendarId);
        return resolve(calendar, from, from.plus(Duration.ofDays(days)));
    }

    /** The allowed ("on") windows the calendar resolves to; empty both for "no rows" and "never allowed". */
    @Transactional(readOnly = true)
    public List<AllowedWindow> allowed(UUID calendarId, Instant from, int days) {
        Calendar calendar = require(calendarId);
        return CalendarResolver.allowedWindows(ZoneId.of(calendar.getTimezone()),
                rules.findByCalendarId(calendarId), overrides.findByCalendarIdOrderByStartDateAsc(calendarId),
                from, from.plus(Duration.ofDays(days)));
    }

    /**
     * The schedule to hand an agent, or null if the device has no calendar assigned.
     * {@code extensionUntil} (nullable) delays any off window that would start before it.
     */
    @Transactional(readOnly = true)
    public AgentSchedule scheduleFor(UUID calendarId, Instant extensionUntil) {
        if (calendarId == null) {
            return null;
        }
        Calendar calendar = calendars.findById(calendarId).orElse(null);
        if (calendar == null) {
            return null;
        }
        Instant now = Instant.now();
        // The horizon ends on a UTC day boundary so the revision does not change just because time passed.
        Instant horizon = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS).plus(AGENT_HORIZON);
        List<OffWindow> windows = applyExtension(resolve(calendar, now, horizon), extensionUntil);
        return new AgentSchedule(revision(calendar.getAction(), windows), calendar.getAction(),
                calendar.getTimezone(), windows);
    }

    /** Effective windows from now with the extension applied; used to work out what "30 more minutes" means. */
    @Transactional(readOnly = true)
    public List<OffWindow> effectiveWindows(UUID calendarId, Instant extensionUntil, Duration ahead) {
        Calendar calendar = require(calendarId);
        Instant now = Instant.now();
        return applyExtension(resolve(calendar, now, now.plus(ahead)), extensionUntil);
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

    private static String revision(CalendarAction action, List<OffWindow> windows) {
        String canonical = action + windows.toString();
        return UUID.nameUUIDFromBytes(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    public Calendar require(UUID id) {
        return calendars.findById(id)
                .orElseThrow(() -> ApiException.notFound("CALENDAR_NOT_FOUND", "No calendar with id " + id));
    }

    private List<OffWindow> resolve(Calendar calendar, Instant from, Instant to) {
        return CalendarResolver.offWindows(ZoneId.of(calendar.getTimezone()),
                rules.findByCalendarId(calendar.getId()),
                overrides.findByCalendarIdOrderByStartDateAsc(calendar.getId()), from, to);
    }

    private void apply(Calendar calendar, CalendarRequest request, Instant now) {
        calendar.setName(request.name().strip());
        calendar.setTimezone(request.timezone());
        calendar.setAction(request.action());
        calendar.setUpdatedAt(now);
        calendars.save(calendar);
        for (Rule rule : request.rules()) {
            rules.save(new CalendarRule(UUID.randomUUID(), calendar.getId(), rule.days(),
                    CalendarResolver.startOrMidnight(rule.startTime()), CalendarResolver.endOrMidnight(rule.endTime()),
                    rule.enabled()));
        }
    }

    private CalendarDto toDto(Calendar calendar) {
        return CalendarDto.from(calendar, rules.findByCalendarId(calendar.getId()),
                overrides.findByCalendarIdOrderByStartDateAsc(calendar.getId()));
    }

    private void validate(CalendarRequest request) {
        try {
            ZoneId.of(request.timezone());
        } catch (DateTimeException e) {
            throw ApiException.badRequest("INVALID_TIMEZONE", "Unknown timezone: " + request.timezone());
        }
        for (Rule rule : request.rules()) {
            if (rule.days() == null || rule.days().isEmpty()) {
                throw ApiException.badRequest("INVALID_RULE", "Each row needs at least one day");
            }
            requireValidWindow(CalendarResolver.startOrMidnight(rule.startTime()),
                    CalendarResolver.endOrMidnight(rule.endTime()), "INVALID_RULE");
        }
    }

    private static void requireValidWindow(java.time.LocalTime start, java.time.LocalTime end, String code) {
        if (!CalendarResolver.isValidWindow(start, end)) {
            throw ApiException.badRequest(code, "The off time (" + end + ") must be after the on time (" + start
                    + "); use 00:00 for midnight. A window can't run past midnight: add a row for the next day instead.");
        }
    }

    private static ApiException nameTaken(String name) {
        return new ApiException(org.springframework.http.HttpStatus.CONFLICT, "CALENDAR_NAME_TAKEN",
                "A schedule named '" + name + "' already exists");
    }
}
