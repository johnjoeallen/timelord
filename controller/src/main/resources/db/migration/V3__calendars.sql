-- Named on/off calendars assigned to devices. A calendar holds weekly rules
-- (the base schedule) and dated overrides that replace the base rule for the
-- nights they cover without ever modifying it.

create table calendar (
    id uuid primary key,
    name varchar(200) not null unique,
    timezone varchar(100) not null,
    action varchar(30) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table calendar_rule (
    id uuid primary key,
    calendar_id uuid not null references calendar(id) on delete cascade,
    days jsonb not null,
    off_time time not null,
    on_time time not null
);

create index calendar_rule_calendar_idx on calendar_rule(calendar_id);

create table calendar_override (
    id uuid primary key,
    calendar_id uuid not null references calendar(id) on delete cascade,
    name varchar(200),
    start_date date not null,
    end_date date not null,
    off_time time not null,
    on_time time not null,
    created_at timestamptz not null
);

create index calendar_override_calendar_idx on calendar_override(calendar_id);

alter table device add column calendar_id uuid references calendar(id) on delete set null;
-- Temporary "a bit longer" grant: off windows are clipped to start no earlier than this.
alter table device add column extension_until timestamptz;
