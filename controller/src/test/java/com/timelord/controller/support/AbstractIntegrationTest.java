package com.timelord.controller.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * "Singleton container" pattern: one Postgres container for the whole test
 * JVM, started once in a static initializer and never stopped explicitly
 * (it dies with the JVM). Deliberately does NOT use {@code @Testcontainers}
 * / {@code @Container} — those annotations tie start/stop to whichever
 * concrete subclass is currently running, and since every subclass here
 * shares this one inherited field, that caused each subclass's teardown to
 * stop the container out from under a still-cached Spring context from a
 * *different* subclass (visible as background {@code @Scheduled} tasks and
 * even live HTTP test requests failing with "Connection refused" against a
 * container index that no longer existed).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("timelord")
            .withUsername("timelord")
            .withPassword("timelord");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Distinct port per test JVM run to avoid clashing with a real
        // controller (default 45821) that might be running locally.
        registry.add("timelord.discovery.port", () -> 45922);
        registry.add("timelord.discovery.announcement-enabled", () -> false);
    }

    @Autowired
    protected TestRestTemplate restTemplate;
}
