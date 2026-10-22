package com.timelord.controller.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.timelord.controller.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/** Runs the real migrations up to V4, inserts calendars the old way, then migrates to the latest and checks the result. */
class ScheduleMigrationIntegrationTest extends AbstractIntegrationTest {

    @Value("${spring.datasource.url}")
    private String url;
    @Value("${spring.datasource.username}")
    private String user;
    @Value("${spring.datasource.password}")
    private String password;

    /** Each test migrates its own copy of the schema from scratch. */
    @BeforeEach
    void dropMigrationSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            execute(c, "drop schema if exists migtest cascade");
        }
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(url, user, password).schemas("migtest").createSchemas(true);
        if (target != null) {
            config.target(MigrationVersion.fromVersion(target));
        }
        return config.load();
    }

    private static void execute(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static List<String> rows(Connection c, UUID calendar) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select days::text, start_time, end_time from schedule_rule where schedule_id = '"
                     + calendar + "' order by start_time, end_time, days::text")) {
            while (rs.next()) {
                out.add(rs.getString(1).replaceAll("[\\[\\]\" ]", "") + " " + rs.getTime(2) + "-" + rs.getTime(3));
            }
        }
        return out;
    }

    @Test
    void existingCalendarsAreConvertedToAllowedWindows() throws Exception {
        flyway("4").migrate();
        UUID school = UUID.randomUUID();
        UUID thursday = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            c.setSchema("migtest");
            for (UUID id : List.of(school, thursday)) {
                execute(c, "insert into calendar values ('" + id + "', 'cal-" + id + "', 'Europe/Dublin', 'SLEEP', now(), now())");
            }
            // The old model: Sun-Thu off 22:30 on 06:30, Fri/Sat off 01:00 (after midnight) on 06:30...
            execute(c, "insert into calendar_rule values (gen_random_uuid(), '" + school
                    + "', '[\"SUNDAY\",\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\"]', '22:30', '06:30')");
            execute(c, "insert into calendar_rule values (gen_random_uuid(), '" + school + "', '[\"FRIDAY\",\"SATURDAY\"]', '01:00', '06:30')");
            // ...and a Thursday off 16:15 on 16:30, plus a Christmas override that took the device off at 01:00.
            execute(c, "insert into calendar_rule values (gen_random_uuid(), '" + thursday + "', '[\"THURSDAY\"]', '16:15', '16:30')");
            execute(c, "insert into calendar_override values (gen_random_uuid(), '" + school
                    + "', 'Christmas', '2026-12-19', '2027-01-05', '01:00', '06:30', now())");
        }

        flyway(null).migrate();

        try (Connection c = DriverManager.getConnection(url, user, password)) {
            c.setSchema("migtest");
            // Allowed Mon-Thu and Sun 06:30-22:30; Fri/Sat from 06:30 to midnight, continuing 00:00-01:00 on Sat/Sun.
            assertThat(rows(c, school)).containsExactlyInAnyOrder(
                    "SATURDAY,SUNDAY 00:00:00-01:00:00",
                    "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,SUNDAY 06:30:00-22:30:00",
                    "FRIDAY,SATURDAY 06:30:00-00:00:00");
            assertThat(rows(c, thursday)).contains("THURSDAY 00:00:00-16:15:00", "THURSDAY 16:30:00-00:00:00");

            // The override became two added windows (06:30..midnight, then midnight..01:00 the next day).
            List<String> overrides = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select name, start_date, end_date, start_time, end_time from schedule_override "
                         + "where schedule_id = '" + school + "' order by start_date")) {
                while (rs.next()) {
                    overrides.add(rs.getString(1) + " " + rs.getDate(2) + ".." + rs.getDate(3) + " " + rs.getTime(4) + "-" + rs.getTime(5));
                }
            }
            assertThat(overrides).containsExactly(
                    "Christmas 2026-12-20..2027-01-06 06:30:00-00:00:00",
                    "Christmas 2026-12-21..2027-01-07 00:00:00-01:00:00");

            // The old columns are gone.
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select count(*) from information_schema.columns where table_schema = 'migtest' "
                         + "and table_name = 'schedule_rule' and column_name in ('off_time', 'on_time')")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    void renamingCalendarsToSchedulesKeepsTheDataAndTheLinks() throws Exception {
        flyway("7").migrate();
        UUID id = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            c.setSchema("migtest");
            execute(c, "insert into calendar values ('" + id + "', 'Kids', 'Europe/Dublin', 'SLEEP', now(), now())");
            execute(c, "insert into calendar_rule (id, calendar_id, days, start_time, end_time) values (gen_random_uuid(), '" + id
                    + "', '[\"MONDAY\"]', '08:00', '10:00')");
            execute(c, "insert into calendar_override (id, calendar_id, name, start_date, end_date, start_time, end_time, created_at) "
                    + "values (gen_random_uuid(), '" + id + "', 'Trip', '2030-01-01', '2030-01-02', '09:00', '12:00', now())");
        }

        flyway(null).migrate();

        try (Connection c = DriverManager.getConnection(url, user, password)) {
            c.setSchema("migtest");
            assertThat(count(c, "select count(*) from schedule where id = '" + id + "' and name = 'Kids'")).isEqualTo(1);
            assertThat(count(c, "select count(*) from schedule_rule where schedule_id = '" + id + "'")).isEqualTo(1);
            assertThat(count(c, "select count(*) from schedule_override where schedule_id = '" + id + "' and enabled")).isEqualTo(1);
            assertThat(count(c, "select count(*) from information_schema.columns where table_schema = 'migtest' "
                    + "and table_name = 'device' and column_name = 'schedule_id'")).isEqualTo(1);
            assertThat(count(c, "select count(*) from information_schema.tables where table_schema = 'migtest' "
                    + "and table_name in ('calendar', 'calendar_rule', 'calendar_override')")).isZero();

            // The foreign keys still cascade after the rename.
            execute(c, "delete from schedule where id = '" + id + "'");
            assertThat(count(c, "select count(*) from schedule_rule where schedule_id = '" + id + "'")).isZero();
            assertThat(count(c, "select count(*) from schedule_override where schedule_id = '" + id + "'")).isZero();
        }
    }

    private static int count(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
