package com.timelord.controller.calendar;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarRuleRepository extends JpaRepository<CalendarRule, UUID> {

    List<CalendarRule> findByCalendarId(UUID calendarId);

    void deleteByCalendarId(UUID calendarId);
}
