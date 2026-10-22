package com.timelord.controller.message;

import jakarta.validation.constraints.NotBlank;

public record MessageRequest(String title, @NotBlank String body, Integer expiresInMinutes) {
}
