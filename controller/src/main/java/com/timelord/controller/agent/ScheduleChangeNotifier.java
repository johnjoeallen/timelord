package com.timelord.controller.agent;

import org.springframework.stereotype.Component;

/** Wakes schedule long-polls (extension, schedule assignment, schedule/override edit). */
@Component
public class ScheduleChangeNotifier extends DeviceWaiters {
}
