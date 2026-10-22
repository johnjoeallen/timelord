package com.timelord.controller.agent;

import com.timelord.controller.schedule.ScheduleAction;
import com.timelord.controller.schedule.OffWindow;
import java.util.List;

/**
 * The resolved schedule handed to an agent: concrete off windows only, so the
 * agent never has to understand rules or overrides and can keep enforcing
 * from its cached copy while the controller is unreachable. {@code revision}
 * changes whenever the windows or action do, so the agent can long-poll for
 * changes cheaply.
 */
public record AgentSchedule(
        String revision,
        ScheduleAction action,
        String timezone,
        List<OffWindow> windows
) {
}
