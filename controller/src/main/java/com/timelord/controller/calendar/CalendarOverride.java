package com.timelord.controller.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Adds an allowed window ({@code startTime} to {@code endTime} within the day, same meaning as for a
 * {@link CalendarRule}) on every date in [{@code startDate}, {@code endDate}] (inclusive; a single
 * day has the same start and end). It is unioned with the weekly rows, so an override can extend a
 * day but never shorten it. A disabled override is kept but adds nothing.
 */
@Entity
@Table(name = "calendar_override")
public class CalendarOverride {

    @Id
    private UUID id;

    @Column(name = "calendar_id", nullable = false)
    private UUID calendarId;

    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean enabled = true;

    protected CalendarOverride() {
    }

    public CalendarOverride(UUID id, UUID calendarId, String name, LocalDate startDate, LocalDate endDate,
                            LocalTime startTime, LocalTime endTime, Instant createdAt) {
        this.id = id;
        this.calendarId = calendarId;
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCalendarId() {
        return calendarId;
    }

    public String getName() {
        return name;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** A disabled override is kept but adds no window, exactly as if it had been removed. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
