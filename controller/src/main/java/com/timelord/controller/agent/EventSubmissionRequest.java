package com.timelord.controller.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record EventSubmissionRequest(
        @NotEmpty @Size(max = 100) @Valid List<EventItem> events
) {
}
