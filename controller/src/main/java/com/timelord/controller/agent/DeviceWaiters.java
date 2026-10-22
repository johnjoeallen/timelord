package com.timelord.controller.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Parks agents' long-poll requests until something relevant to that device
 * changes. Waiters re-check their own state when woken, so over-notifying is
 * harmless.
 */
public class DeviceWaiters {

    private final Map<UUID, List<Runnable>> waiters = new ConcurrentHashMap<>();

    /** Registers a one-shot callback; returns a handle to cancel it (on timeout). */
    public Runnable await(UUID deviceId, Runnable onChange) {
        List<Runnable> list = waiters.computeIfAbsent(deviceId, k -> new CopyOnWriteArrayList<>());
        list.add(onChange);
        return () -> list.remove(onChange);
    }

    public void deviceChanged(UUID deviceId) {
        List<Runnable> list = waiters.remove(deviceId);
        if (list != null) {
            list.forEach(Runnable::run);
        }
    }

    public void allChanged() {
        forEachDevice(this::deviceChanged);
    }

    private void forEachDevice(Consumer<UUID> action) {
        new ArrayList<>(waiters.keySet()).forEach(action);
    }
}
