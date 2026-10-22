package com.timelord.controller.schedule;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRuleRepository extends JpaRepository<ScheduleRule, UUID> {

    List<ScheduleRule> findByScheduleId(UUID scheduleId);

    void deleteByScheduleId(UUID scheduleId);
}
