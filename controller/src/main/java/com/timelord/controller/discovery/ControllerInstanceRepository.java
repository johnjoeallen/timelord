package com.timelord.controller.discovery;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ControllerInstanceRepository extends JpaRepository<ControllerInstance, UUID> {

    @Query("select c from ControllerInstance c order by c.createdAt asc")
    List<ControllerInstance> findAllOrderByCreatedAtAsc();
}
