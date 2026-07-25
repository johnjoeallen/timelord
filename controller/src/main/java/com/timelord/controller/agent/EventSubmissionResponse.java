package com.timelord.controller.agent;

import java.util.List;

public record EventSubmissionResponse(
        int accepted,
        int duplicates,
        int rejected,
        List<EventResult> results
) {
}
