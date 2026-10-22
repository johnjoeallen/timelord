package com.timelord.controller.calendar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarOverrideRepository extends JpaRepository<CalendarOverride, UUID> {

    List<CalendarOverride> findByCalendarIdOrderByStartDateAsc(UUID calendarId);

    Optional<CalendarOverride> findByIdAndCalendarId(UUID id, UUID calendarId);
}
