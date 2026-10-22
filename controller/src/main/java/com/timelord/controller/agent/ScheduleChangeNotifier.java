package com.timelord.controller.agent;

import org.springframework.stereotype.Component;

/** Wakes schedule long-polls (extension, calendar assignment, calendar/override edit). */
@Component
public class ScheduleChangeNotifier extends DeviceWaiters {
}
