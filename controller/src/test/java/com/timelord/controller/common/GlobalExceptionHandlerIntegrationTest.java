package com.timelord.controller.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.timelord.controller.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GlobalExceptionHandlerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void unmatchedPathReturns404NotAGeneric500() {
        var response = restTemplate.getForEntity("/this-path-does-not-exist", ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void missingStaticAssetReturns404NotAGeneric500() {
        var response = restTemplate.getForEntity("/apple-touch-icon.png", ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
