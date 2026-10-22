package com.timelord.controller.schedule;

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
 * One row of the weekly schedule: on each of {@code days} the device is allowed to be on from
 * {@code startTime} until {@code endTime}, within that day. An end time of 00:00 means midnight at the
 * end of the day (so 00:00 to 00:00 is the whole day); otherwise the end must be after the start. Any
 * number of rows may cover the same day; their windows are unioned. A disabled row is ignored.
 */
@Entity
@Table(name = "schedule_rule")
public class ScheduleRule {

    @Id
    private UUID id;

    @Column(name = "schedule_id", nullable = false)
    private UUID scheduleId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<DayOfWeek> days = new ArrayList<>();

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private boolean enabled = true;

    protected ScheduleRule() {
    }

    public ScheduleRule(UUID id, UUID scheduleId, List<DayOfWeek> days, LocalTime startTime, LocalTime endTime) {
        this(id, scheduleId, days, startTime, endTime, true);
    }

    public ScheduleRule(UUID id, UUID scheduleId, List<DayOfWeek> days, LocalTime startTime, LocalTime endTime,
                        boolean enabled) {
        this.id = id;
        this.scheduleId = scheduleId;
        this.days = new ArrayList<>(days);
        this.startTime = startTime;
        this.endTime = endTime;
        this.enabled = enabled;
    }

    public UUID getId() {
        return id;
    }

    public UUID getScheduleId() {
        return scheduleId;
    }

    public List<DayOfWeek> getDays() {
        return days;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    /** A disabled row is kept but gives no window, exactly as if it had been deleted. */
    public boolean isEnabled() {
        return enabled;
    }
}
