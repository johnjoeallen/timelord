package db.migration;

import com.timelord.controller.schedule.LegacyScheduleConverter;
import com.timelord.controller.schedule.LegacyScheduleConverter.LegacyRule;
import com.timelord.controller.schedule.LegacyScheduleConverter.Row;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Time;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts calendars from the "off window" model (days + off time + on time, one rule per day) to
 * allowed ("on") windows (days + start time + end time, any number of rows per day, overrides add
 * windows rather than replacing them, and every window stays within one day). Weekly rules are
 * converted exactly via {@link LegacyScheduleConverter}; each override becomes the equivalent added
 * window(s), split at midnight where they crossed it.
 *
 * <p>Two things cannot carry over: an override can no longer <em>narrow</em> a day, and a calendar
 * whose old rules left no allowed time at all ends up with no rows (which restricts nothing).
 */
public class V5__OnWindows extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V5__OnWindows.class);
    private static final Pattern DAY_NAME = Pattern.compile("[A-Z]+");

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        try (Statement st = c.createStatement()) {
            st.execute("alter table calendar_rule add column start_time time, add column end_time time");
            st.execute("alter table calendar_rule alter column off_time drop not null, alter column on_time drop not null");
            st.execute("alter table calendar_override add column start_time time, add column end_time time");
            st.execute("alter table calendar_override alter column off_time drop not null, alter column on_time drop not null");
        }
        convertRules(c);
        convertOverrides(c);
        try (Statement st = c.createStatement()) {
            st.execute("alter table calendar_rule drop column off_time, drop column on_time");
            st.execute("alter table calendar_rule alter column start_time set not null, alter column end_time set not null");
            st.execute("alter table calendar_override drop column off_time, drop column on_time");
            st.execute("alter table calendar_override alter column start_time set not null, alter column end_time set not null");
        }
    }

    private void convertRules(Connection c) throws Exception {
        Map<UUID, List<LegacyRule>> byCalendar = new LinkedHashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select calendar_id, days::text, off_time, on_time from calendar_rule where start_time is null")) {
            while (rs.next()) {
                byCalendar.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new LegacyRule(parseDays(rs.getString(2)), rs.getTime(3).toLocalTime(), rs.getTime(4).toLocalTime()));
            }
        }
        for (Map.Entry<UUID, List<LegacyRule>> entry : byCalendar.entrySet()) {
            List<Row> rows = LegacyScheduleConverter.toAllowedRows(entry.getValue());
            if (rows.isEmpty()) {
                log.warn("Calendar {} left no allowed time under the old rules; it now has no rows and restricts nothing", entry.getKey());
            }
            try (PreparedStatement delete = c.prepareStatement("delete from calendar_rule where calendar_id = ? and start_time is null")) {
                delete.setObject(1, entry.getKey());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "insert into calendar_rule (id, calendar_id, days, start_time, end_time) values (?, ?, ?::jsonb, ?, ?)")) {
                for (Row row : rows) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, entry.getKey());
                    insert.setString(3, toJson(row.days()));
                    insert.setTime(4, Time.valueOf(row.start()));
                    insert.setTime(5, Time.valueOf(row.end()));
                    insert.executeUpdate();
                }
            }
        }
    }

    private void convertOverrides(Connection c) throws Exception {
        record Old(UUID id, LocalDate start, LocalDate end, LocalTime off, LocalTime on) {
        }
        List<Old> olds = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select id, start_date, end_date, off_time, on_time from calendar_override")) {
            while (rs.next()) {
                olds.add(new Old(rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(), rs.getDate(3).toLocalDate(),
                        rs.getTime(4).toLocalTime(), rs.getTime(5).toLocalTime()));
            }
        }
        try (PreparedStatement update = c.prepareStatement(
                "update calendar_override set start_date = ?, end_date = ?, start_time = ?, end_time = ? where id = ?");
             PreparedStatement insert = c.prepareStatement(
                     "insert into calendar_override (id, calendar_id, name, start_date, end_date, start_time, end_time, created_at) "
                             + "select ?, calendar_id, name, ?, ?, ?, ?, created_at from calendar_override where id = ?")) {
            for (Old o : olds) {
                // The old override took the device off at `off` and back on at `on`; the allowed time it
                // implied starts at `on` (on the day the device came back) and lasts until the next `off`,
                // split at midnight so each added window stays within a day.
                int shift = LegacyScheduleConverter.onDayShift(o.off(), o.on());
                List<LegacyScheduleConverter.OverrideWindow> windows = LegacyScheduleConverter.overrideWindows(o.off(), o.on());
                LegacyScheduleConverter.OverrideWindow first = windows.get(0);
                update.setDate(1, Date.valueOf(o.start().plusDays(shift + first.dayOffset())));
                update.setDate(2, Date.valueOf(o.end().plusDays(shift + first.dayOffset())));
                update.setTime(3, Time.valueOf(first.start()));
                update.setTime(4, Time.valueOf(first.end()));
                update.setObject(5, o.id());
                update.addBatch();
                for (LegacyScheduleConverter.OverrideWindow extra : windows.subList(1, windows.size())) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setDate(2, Date.valueOf(o.start().plusDays(shift + extra.dayOffset())));
                    insert.setDate(3, Date.valueOf(o.end().plusDays(shift + extra.dayOffset())));
                    insert.setTime(4, Time.valueOf(extra.start()));
                    insert.setTime(5, Time.valueOf(extra.end()));
                    insert.setObject(6, o.id());
                    insert.addBatch();
                }
            }
            update.executeBatch();
            insert.executeBatch();
        }
    }

    private static EnumSet<DayOfWeek> parseDays(String json) {
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        Matcher m = DAY_NAME.matcher(json);
        while (m.find()) {
            days.add(DayOfWeek.valueOf(m.group()));
        }
        return days;
    }

    private static String toJson(java.util.Set<DayOfWeek> days) {
        StringBuilder sb = new StringBuilder("[");
        for (DayOfWeek day : days) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(day.name()).append('"');
        }
        return sb.append(']').toString();
    }
}
