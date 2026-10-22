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
 * Replaces the weekly rule for every night that starts on a date in
 * [{@code startDate}, {@code endDate}] (inclusive; a single day has the same
 * start and end). Same night semantics as {@link CalendarRule}.
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

    @Column(name = "off_time", nullable = false)
    private LocalTime offTime;

    @Column(name = "on_time", nullable = false)
    private LocalTime onTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CalendarOverride() {
    }

    public CalendarOverride(UUID id, UUID calendarId, String name, LocalDate startDate, LocalDate endDate,
                            LocalTime offTime, LocalTime onTime, Instant createdAt) {
        this.id = id;
        this.calendarId = calendarId;
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
        this.offTime = offTime;
        this.onTime = onTime;
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

    public LocalTime getOffTime() {
        return offTime;
    }

    public LocalTime getOnTime() {
        return onTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
