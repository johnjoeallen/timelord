package com.timelord.controller.dashboard;

import com.timelord.controller.agent.ScheduleChangeNotifier;
import com.timelord.controller.calendar.CalendarAction;
import com.timelord.controller.calendar.CalendarDto;
import com.timelord.controller.calendar.CalendarRequest;
import com.timelord.controller.calendar.CalendarService;
import com.timelord.controller.calendar.OverrideRequest;
import com.timelord.controller.common.ApiException;
import com.timelord.controller.device.DeviceService;
import com.timelord.controller.timezone.RequestZoneResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-rendered calendar management. Plain form posts with redirect-after-post
 * (no client-side JS), delegating all validation to {@link CalendarService}.
 */
@Controller
public class CalendarDashboardController {

    /** Blank rows offered below the existing rules so new ones can be added without extra clicks. */
    private static final int SPARE_RULE_ROWS = 2;
    private static final int PREVIEW_DAYS = 14;

    private final CalendarService calendarService;
    private final DeviceService deviceService;
    private final ScheduleChangeNotifier scheduleChanges;
    private final RequestZoneResolver zoneResolver;

    public CalendarDashboardController(CalendarService calendarService, DeviceService deviceService,
                                       ScheduleChangeNotifier scheduleChanges, RequestZoneResolver zoneResolver) {
        this.calendarService = calendarService;
        this.deviceService = deviceService;
        this.scheduleChanges = scheduleChanges;
        this.zoneResolver = zoneResolver;
    }

    @ModelAttribute("zone")
    public ZoneId zone(HttpServletRequest request) {
        return zoneResolver.resolve(request);
    }

    /** One editable weekly-rule row; a row with no days ticked is ignored on save. */
    public static class RuleForm {
        private List<DayOfWeek> days = new ArrayList<>();
        @DateTimeFormat(pattern = "HH:mm")
        private LocalTime offTime;
        @DateTimeFormat(pattern = "HH:mm")
        private LocalTime onTime;

        public List<DayOfWeek> getDays() {
            return days;
        }

        public void setDays(List<DayOfWeek> days) {
            this.days = days;
        }

        public LocalTime getOffTime() {
            return offTime;
        }

        public void setOffTime(LocalTime offTime) {
            this.offTime = offTime;
        }

        public LocalTime getOnTime() {
            return onTime;
        }

        public void setOnTime(LocalTime onTime) {
            this.onTime = onTime;
        }
    }

    public static class CalendarForm {
        private String name;
        private String timezone;
        private CalendarAction action = CalendarAction.SLEEP;
        private List<RuleForm> rules = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public CalendarAction getAction() {
            return action;
        }

        public void setAction(CalendarAction action) {
            this.action = action;
        }

        public List<RuleForm> getRules() {
            return rules;
        }

        public void setRules(List<RuleForm> rules) {
            this.rules = rules;
        }

        CalendarRequest toRequest() {
            List<CalendarDto.Rule> out = new ArrayList<>();
            for (RuleForm r : rules) {
                if (r.days == null || r.days.isEmpty()) {
                    continue;
                }
                if (r.offTime == null || r.onTime == null) {
                    throw ApiException.badRequest("INVALID_RULE", "Each rule with days needs an off and an on time");
                }
                out.add(new CalendarDto.Rule(new ArrayList<>(new TreeSet<>(r.days)), r.offTime, r.onTime));
            }
            return new CalendarRequest(name == null ? "" : name.strip(), timezone == null ? "" : timezone.strip(), action, out);
        }
    }

    @GetMapping("/calendars")
    public String list(Model model, HttpServletRequest request) {
        model.addAttribute("calendars", calendarService.list());
        model.addAttribute("timezones", timezones());
        model.addAttribute("defaultZone", zoneResolver.resolve(request).getId());
        model.addAttribute("actions", CalendarAction.values());
        return "calendars";
    }

    @PostMapping("/calendars")
    public String create(@ModelAttribute CalendarForm form, RedirectAttributes redirect) {
        form.rules = new ArrayList<>();
        try {
            CalendarDto created = calendarService.create(form.toRequest());
            redirect.addFlashAttribute("message", "Calendar created. Add weekly rules below.");
            return "redirect:/calendars/" + created.id();
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/calendars";
        }
    }

    @GetMapping("/calendars/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        CalendarDto calendar = calendarService.get(id);
        ZoneId calendarZone = ZoneId.of(calendar.timezone());

        List<RuleForm> rows = new ArrayList<>();
        for (CalendarDto.Rule r : calendar.rules()) {
            RuleForm row = new RuleForm();
            row.setDays(new ArrayList<>(r.days()));
            row.setOffTime(r.offTime());
            row.setOnTime(r.onTime());
            rows.add(row);
        }
        for (int i = 0; i < SPARE_RULE_ROWS; i++) {
            rows.add(new RuleForm());
        }

        model.addAttribute("calendar", calendar);
        model.addAttribute("ruleRows", rows);
        model.addAttribute("days", DayOfWeek.values());
        model.addAttribute("actions", CalendarAction.values());
        model.addAttribute("timezones", timezones());
        model.addAttribute("calendarZone", calendarZone);
        model.addAttribute("windows", calendarService.preview(id, java.time.Instant.now(), PREVIEW_DAYS));
        model.addAttribute("today", LocalDate.now(calendarZone));
        return "calendar-detail";
    }

    @PostMapping("/calendars/{id}")
    public String update(@PathVariable UUID id, @ModelAttribute CalendarForm form, RedirectAttributes redirect) {
        return run(redirect, "/calendars/" + id, "Calendar saved.", () -> {
            calendarService.update(id, form.toRequest());
            scheduleChanges.allChanged();
        });
    }

    @PostMapping("/calendars/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        return run(redirect, "/calendars", "Calendar deleted.", () -> {
            calendarService.delete(id);
            scheduleChanges.allChanged();
        });
    }

    @PostMapping("/calendars/{id}/overrides")
    public String addOverride(@PathVariable UUID id,
                              @RequestParam(required = false) String name,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                              @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime offTime,
                              @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime onTime,
                              RedirectAttributes redirect) {
        return run(redirect, "/calendars/" + id, "Override added.", () -> {
            // A blank end date means a single day.
            LocalDate end = endDate != null ? endDate : startDate;
            calendarService.addOverride(id, new OverrideRequest(name == null || name.isBlank() ? null : name.strip(),
                    startDate, end, offTime, onTime));
            scheduleChanges.allChanged();
        });
    }

    @PostMapping("/calendars/{id}/overrides/{overrideId}/delete")
    public String removeOverride(@PathVariable UUID id, @PathVariable UUID overrideId, RedirectAttributes redirect) {
        return run(redirect, "/calendars/" + id, "Override removed.", () -> {
            calendarService.removeOverride(id, overrideId);
            scheduleChanges.allChanged();
        });
    }

    @PostMapping("/devices/{deviceId}/calendar")
    public String assign(@PathVariable UUID deviceId, @RequestParam(required = false) UUID calendarId,
                         RedirectAttributes redirect) {
        return run(redirect, "/devices/" + deviceId, "Calendar updated.", () -> {
            deviceService.assignCalendar(deviceId, calendarId);
            scheduleChanges.deviceChanged(deviceId);
        });
    }

    @PostMapping("/devices/{deviceId}/extend")
    public String extend(@PathVariable UUID deviceId, @RequestParam int minutes, RedirectAttributes redirect) {
        return run(redirect, "/devices/" + deviceId, "Granted " + minutes + " more minutes.", () -> {
            if (minutes < 1 || minutes > 240) {
                throw ApiException.badRequest("INVALID_EXTENSION", "minutes must be between 1 and 240");
            }
            deviceService.extend(deviceId, minutes);
            scheduleChanges.deviceChanged(deviceId);
        });
    }

    @PostMapping("/devices/{deviceId}/extend/clear")
    public String clearExtension(@PathVariable UUID deviceId, RedirectAttributes redirect) {
        return run(redirect, "/devices/" + deviceId, "Extension cleared.", () -> {
            deviceService.clearExtension(deviceId);
            scheduleChanges.deviceChanged(deviceId);
        });
    }

    private String run(RedirectAttributes redirect, String target, String success, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("message", success);
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + target;
    }

    private static List<String> timezones() {
        return new TreeSet<>(ZoneId.getAvailableZoneIds()).stream().filter(z -> z.contains("/") && !z.startsWith("Etc/")).toList();
    }
}
