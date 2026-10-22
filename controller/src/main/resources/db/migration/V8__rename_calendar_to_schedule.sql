-- "Calendar" became "schedule" everywhere users see it; the tables and columns follow. Data and
-- foreign keys are untouched: these are renames only.
alter table calendar rename to schedule;
alter table calendar_rule rename to schedule_rule;
alter table calendar_override rename to schedule_override;

alter table schedule_rule rename column calendar_id to schedule_id;
alter table schedule_override rename column calendar_id to schedule_id;
alter table device rename column calendar_id to schedule_id;

-- Keep the generated constraint and index names in step with the tables.
alter table schedule rename constraint calendar_pkey to schedule_pkey;
alter table schedule rename constraint calendar_name_key to schedule_name_key;
alter table schedule_rule rename constraint calendar_rule_pkey to schedule_rule_pkey;
alter table schedule_rule rename constraint calendar_rule_calendar_id_fkey to schedule_rule_schedule_id_fkey;
alter table schedule_override rename constraint calendar_override_pkey to schedule_override_pkey;
alter table schedule_override rename constraint calendar_override_calendar_id_fkey to schedule_override_schedule_id_fkey;
alter table device rename constraint device_calendar_id_fkey to device_schedule_id_fkey;
alter index calendar_rule_calendar_idx rename to schedule_rule_schedule_idx;
alter index calendar_override_calendar_idx rename to schedule_override_schedule_idx;
