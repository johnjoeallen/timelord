package com.timelord.controller.schedule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleOverrideRepository extends JpaRepository<ScheduleOverride, UUID> {

    List<ScheduleOverride> findByScheduleIdOrderByStartDateAsc(UUID scheduleId);

    Optional<ScheduleOverride> findByIdAndScheduleId(UUID id, UUID scheduleId);
}
