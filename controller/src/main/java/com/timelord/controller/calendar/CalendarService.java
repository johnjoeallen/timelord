package com.timelord.controller.calendar;

import com.timelord.controller.agent.AgentSchedule;
import com.timelord.controller.calendar.CalendarDto.Rule;
import com.timelord.controller.common.ApiException;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarService {

    /** How far ahead the agent is told about off windows; it refreshes on every heartbeat. */
    static final Duration AGENT_HORIZON = Duration.ofDays(7);

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
        requireDistinctTimes(request.offTime(), request.onTime());
        overrides.save(new CalendarOverride(UUID.randomUUID(), calendarId, request.name(), request.startDate(),
                request.endDate(), request.offTime(), request.onTime(), Instant.now()));
        return toDto(calendar);
    }

    @Transactional
    public CalendarDto removeOverride(UUID calendarId, UUID overrideId) {
        Calendar calendar = require(calendarId);
        CalendarOverride override = overrides.findByIdAndCalendarId(overrideId, calendarId)
                .orElseThrow(() -> ApiException.notFound("OVERRIDE_NOT_FOUND",
                        "No override " + overrideId + " on calendar " + calendarId));
        overrides.delete(override);
        return toDto(calendar);
    }

    @Transactional(readOnly = true)
    public List<OffWindow> preview(UUID calendarId, Instant from, int days) {
        Calendar calendar = require(calendarId);
        return resolve(calendar, from, from.plus(Duration.ofDays(days)));
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
        List<OffWindow> windows = applyExtension(resolve(calendar, now, now.plus(AGENT_HORIZON)), extensionUntil);
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
        return CalendarResolver.windows(ZoneId.of(calendar.getTimezone()),
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
            rules.save(new CalendarRule(UUID.randomUUID(), calendar.getId(), rule.days(), rule.offTime(), rule.onTime()));
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
        Set<DayOfWeek> seen = EnumSet.noneOf(DayOfWeek.class);
        for (Rule rule : request.rules()) {
            if (rule.days() == null || rule.days().isEmpty() || rule.offTime() == null || rule.onTime() == null) {
                throw ApiException.badRequest("INVALID_RULE", "Each rule needs days, offTime and onTime");
            }
            requireDistinctTimes(rule.offTime(), rule.onTime());
            for (DayOfWeek day : rule.days()) {
                if (!seen.add(day)) {
                    throw ApiException.badRequest("INVALID_RULE", day + " appears in more than one rule");
                }
            }
        }
    }

    private static void requireDistinctTimes(java.time.LocalTime off, java.time.LocalTime on) {
        if (off.equals(on)) {
            throw ApiException.badRequest("INVALID_RULE", "offTime and onTime must differ");
        }
    }

    private static ApiException nameTaken(String name) {
        return new ApiException(org.springframework.http.HttpStatus.CONFLICT, "CALENDAR_NAME_TAKEN",
                "A calendar named '" + name + "' already exists");
    }
}
