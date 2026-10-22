package com.timelord.controller.message;

import com.timelord.controller.agent.DeviceWaiters;
import org.springframework.stereotype.Component;

/** Wakes message long-polls when a message is queued for a device. */
@Component
public class MessageChangeNotifier extends DeviceWaiters {
}
