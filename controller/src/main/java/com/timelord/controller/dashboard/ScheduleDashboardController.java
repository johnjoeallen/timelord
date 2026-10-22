package com.timelord.controller.dashboard;

import com.timelord.controller.agent.ScheduleChangeNotifier;
import com.timelord.controller.schedule.ScheduleAction;
import com.timelord.controller.schedule.ScheduleDto;
import com.timelord.controller.schedule.ScheduleRequest;
import com.timelord.controller.schedule.ScheduleService;
import com.timelord.controller.schedule.OverrideRequest;
import com.timelord.controller.common.ApiException;
import com.timelord.controller.device.DeviceService;
import com.timelord.controller.message.MessageRequest;
import com.timelord.controller.message.MessageService;
import com.timelord.controller.timezone.RequestZoneResolver;
import com.timelord.controller.timezone.TimeFormat;
import com.timelord.controller.schedule.ScheduleResolver;
import com.timelord.controller.schedule.ScheduleRule;
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
 * Server-rendered schedule management. Plain form posts with redirect-after-post
 * (no client-side JS), delegating all validation to {@link ScheduleService}.
 */
@Controller
public class ScheduleDashboardController {

    /** The entry row kept below the existing rows; the page's + button commits it and adds a fresh one. */
    private static final int SPARE_RULE_ROWS = 1;
    private static final int PREVIEW_DAYS = 14;

    private final ScheduleService scheduleService;
    private final DeviceService deviceService;
    private final ScheduleChangeNotifier scheduleChanges;
    private final RequestZoneResolver zoneResolver;
    private final MessageService messageService;
    private final TimeFormat timeFormat;

    public ScheduleDashboardController(ScheduleService scheduleService, DeviceService deviceService,
                                       ScheduleChangeNotifier scheduleChanges, RequestZoneResolver zoneResolver,
                                       MessageService messageService, TimeFormat timeFormat) {
        this.messageService = messageService;
        this.timeFormat = timeFormat;
        this.scheduleService = scheduleService;
        this.deviceService = deviceService;
        this.scheduleChanges = scheduleChanges;
        this.zoneResolver = zoneResolver;
    }

    @ModelAttribute("zone")
    public ZoneId zone(HttpServletRequest request) {
        return zoneResolver.resolve(request);
    }

    /** One editable weekly-schedule row; a row with nothing filled in is ignored on save. */
    public static class RuleForm {
        private List<DayOfWeek> days = new ArrayList<>();
        /** False for a row that is switched off but not yet deleted (the - button). */
        private boolean enabled = true;
        @DateTimeFormat(pattern = "HH:mm")
        private LocalTime startTime;
        @DateTimeFormat(pattern = "HH:mm")
        private LocalTime endTime;
        /** Display only: the next window this row gives, so a surprising row is obvious at once. */
        private String preview;

        public List<DayOfWeek> getDays() {
            return days;
        }

        public void setDays(List<DayOfWeek> days) {
            this.days = days;
        }

        public LocalTime getStartTime() {
            return startTime;
        }

        public void setStartTime(LocalTime startTime) {
            this.startTime = startTime;
        }

        public LocalTime getEndTime() {
            return endTime;
        }

        public void setEndTime(LocalTime endTime) {
            this.endTime = endTime;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        boolean isBlank() {
            return (days == null || days.isEmpty()) && startTime == null && endTime == null;
        }

        public String getPreview() {
            return preview;
        }

        public void setPreview(String preview) {
            this.preview = preview;
        }
    }

    public static class ScheduleForm {
        private String name;
        private String timezone;
        private ScheduleAction action = ScheduleAction.SLEEP;
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

        public ScheduleAction getAction() {
            return action;
        }

        public void setAction(ScheduleAction action) {
            this.action = action;
        }

        public List<RuleForm> getRules() {
            return rules;
        }

        public void setRules(List<RuleForm> rules) {
            this.rules = rules;
        }

        ScheduleRequest toRequest() {
            List<ScheduleDto.Rule> out = new ArrayList<>();
            for (RuleForm r : rules) {
                if (r.isBlank()) {
                    continue;
                }
                // A row with times but no days is passed on so the save is refused, not silently dropped.
                List<DayOfWeek> days = r.days == null ? new ArrayList<>() : new ArrayList<>(new TreeSet<>(r.days));
                out.add(new ScheduleDto.Rule(days, r.startTime, r.endTime, r.enabled));
            }
            return new ScheduleRequest(name == null ? "" : name.strip(), timezone == null ? "" : timezone.strip(), action, out);
        }
    }

    @GetMapping("/schedules")
    public String list(Model model, HttpServletRequest request) {
        model.addAttribute("schedules", scheduleService.list());
        model.addAttribute("timezones", timezones());
        model.addAttribute("defaultZone", zoneResolver.resolve(request).getId());
        model.addAttribute("actions", ScheduleAction.values());
        return "schedules";
    }

    @PostMapping("/schedules")
    public String create(@ModelAttribute ScheduleForm form, RedirectAttributes redirect) {
        form.rules = new ArrayList<>();
        try {
            ScheduleDto created = scheduleService.create(form.toRequest());
            redirect.addFlashAttribute("message", "Schedule created. Add weekly rows below.");
            return "redirect:/schedules/" + created.id();
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/schedules";
        }
    }

    @GetMapping("/schedules/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        ScheduleDto schedule = scheduleService.get(id);
        ZoneId scheduleZone = ZoneId.of(schedule.timezone());

        List<RuleForm> rows = new ArrayList<>();
        for (ScheduleDto.Rule r : schedule.rules()) {
            RuleForm row = new RuleForm();
            row.setDays(new ArrayList<>(r.days()));
            row.setStartTime(r.startTime());
            row.setEndTime(r.endTime());
            row.setEnabled(r.enabled());
            row.setPreview(r.enabled() ? previewOf(r, scheduleZone) : null);
            rows.add(row);
        }
        for (int i = 0; i < SPARE_RULE_ROWS; i++) {
            rows.add(new RuleForm());
        }

        model.addAttribute("schedule", schedule);
        model.addAttribute("ruleRows", rows);
        model.addAttribute("hasActiveRows", schedule.rules().stream().anyMatch(ScheduleDto.Rule::enabled));
        model.addAttribute("days", DayOfWeek.values());
        model.addAttribute("actions", ScheduleAction.values());
        model.addAttribute("timezones", timezones());
        model.addAttribute("scheduleZone", scheduleZone);
        model.addAttribute("allowedWindows", scheduleService.allowed(id, java.time.Instant.now(), PREVIEW_DAYS));
        model.addAttribute("today", LocalDate.now(scheduleZone));
        return "schedule-detail";
    }

    @PostMapping("/schedules/{id}")
    public String update(@PathVariable UUID id, @ModelAttribute ScheduleForm form, RedirectAttributes redirect) {
        return run(redirect, "/schedules/" + id, "Schedule saved.", () -> {
            scheduleService.update(id, form.toRequest());
            scheduleChanges.allChanged();
        });
    }

    @PostMapping("/schedules/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        return run(redirect, "/schedules", "Schedule deleted.", () -> {
            scheduleService.delete(id);
            scheduleChanges.allChanged();
        });
    }

    @PostMapping("/schedules/{id}/overrides")
    public String addOverride(@PathVariable UUID id,
                              @RequestParam(required = false) String name,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                              @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime startTime,
                              @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime endTime,
                              RedirectAttributes redirect) {
        return run(redirect, "/schedules/" + id, "Override added.", () -> {
            // A blank end date means a single day.
            LocalDate end = endDate != null ? endDate : startDate;
            scheduleService.addOverride(id, new OverrideRequest(name == null || name.isBlank() ? null : name.strip(),
                    startDate, end, startTime, endTime));
            scheduleChanges.allChanged();
        });
    }

    @PostMapping("/schedules/{id}/overrides/{overrideId}/delete")
    public String removeOverride(@PathVariable UUID id, @PathVariable UUID overrideId, RedirectAttributes redirect) {
        return run(redirect, "/schedules/" + id, "Override removed.", () -> {
            scheduleService.removeOverride(id, overrideId);
            scheduleChanges.allChanged();
        });
    }

    @PostMapping("/schedules/{id}/overrides/{overrideId}/enabled")
    public String setOverrideEnabled(@PathVariable UUID id, @PathVariable UUID overrideId,
                                     @RequestParam boolean enabled, RedirectAttributes redirect) {
        return run(redirect, "/schedules/" + id, enabled ? "Override enabled." : "Override disabled.", () -> {
            scheduleService.setOverrideEnabled(id, overrideId, enabled);
            scheduleChanges.allChanged();
        });
    }

    @PostMapping("/devices/{deviceId}/messages")
    public String sendMessage(@PathVariable UUID deviceId, @RequestParam(required = false) String title,
                              @RequestParam String body, @RequestParam(required = false) Integer expiresInMinutes,
                              RedirectAttributes redirect) {
        return run(redirect, "/devices/" + deviceId, "Message sent.",
                () -> messageService.send(deviceId, new MessageRequest(title, body, expiresInMinutes)));
    }

    @PostMapping("/messages")
    public String broadcastMessage(@RequestParam(required = false) String title, @RequestParam String body,
                                   @RequestParam(required = false) Integer expiresInMinutes, RedirectAttributes redirect) {
        try {
            int count = messageService.sendToAll(new MessageRequest(title, body, expiresInMinutes));
            redirect.addFlashAttribute("message", "Message sent to " + count + " device(s).");
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/devices/{deviceId}/delete")
    public String deleteDevice(@PathVariable UUID deviceId, RedirectAttributes redirect) {
        return run(redirect, "/", "Device deleted.", () -> {
            deviceService.delete(deviceId);
            scheduleChanges.deviceChanged(deviceId);
        });
    }

    @PostMapping("/devices/{deviceId}/schedule")
    public String assign(@PathVariable UUID deviceId, @RequestParam(required = false) UUID scheduleId,
                         RedirectAttributes redirect) {
        return run(redirect, "/devices/" + deviceId, "Schedule updated.", () -> {
            deviceService.assignSchedule(deviceId, scheduleId);
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

    /** The row's next allowed window in the schedule's own timezone, e.g. "Next: Thu 22 Oct 18:30 \u2192 Thu 22 Oct 23:30". */
    private String previewOf(ScheduleDto.Rule rule, ZoneId zone) {
        ScheduleRule single = new ScheduleRule(UUID.randomUUID(), UUID.randomUUID(), rule.days(), rule.startTime(),
                rule.endTime());
        java.time.Instant now = java.time.Instant.now();
        return ScheduleResolver.allowedWindows(zone, List.of(single), List.of(), now, now.plus(Duration.ofDays(9)))
                .stream().findFirst()
                .map(w -> "Next: " + timeFormat.format(w.onAt(), zone, "EEE d MMM HH:mm") + " \u2192 "
                        + timeFormat.format(w.offAt(), zone, "EEE d MMM HH:mm"))
                .orElse(null);
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
