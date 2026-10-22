package com.timelord.controller.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One line of the weekly base schedule: on each of {@code days}, the night
 * that starts that evening goes off at {@code offTime} and is allowed on
 * again at {@code onTime} the following morning. An off time earlier than the
 * on time (e.g. 01:00 vs 06:30) falls after midnight, i.e. on the next
 * calendar day.
 */
@Entity
@Table(name = "calendar_rule")
public class CalendarRule {

    @Id
    private UUID id;

    @Column(name = "calendar_id", nullable = false)
    private UUID calendarId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<DayOfWeek> days = new ArrayList<>();

    @Column(name = "off_time", nullable = false)
    private LocalTime offTime;

    @Column(name = "on_time", nullable = false)
    private LocalTime onTime;

    protected CalendarRule() {
    }

    public CalendarRule(UUID id, UUID calendarId, List<DayOfWeek> days, LocalTime offTime, LocalTime onTime) {
        this.id = id;
        this.calendarId = calendarId;
        this.days = new ArrayList<>(days);
        this.offTime = offTime;
        this.onTime = onTime;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCalendarId() {
        return calendarId;
    }

    public List<DayOfWeek> getDays() {
        return days;
    }

    public LocalTime getOffTime() {
        return offTime;
    }

    public LocalTime getOnTime() {
        return onTime;
    }
}
