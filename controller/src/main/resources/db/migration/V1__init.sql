-- Phase 1 schema: unauthenticated agent registration, heartbeats, and events.
-- Two deliberate deviations from the design brief's suggested DDL:
--  - current_user is a reserved word / builtin function in Postgres, so the
--    "current user" columns are named current_username; the JSON API field
--    stays currentUser.
--  - source_ip uses varchar(45) instead of inet: Hibernate 6 has no built-in
--    inet mapping, and Phase 1 doesn't need range/subnet queries, so a plain
--    string avoids a custom AttributeConverter for no real benefit yet.

create table controller_instance (
    id uuid primary key,
    name varchar(200) not null,
    public_url varchar(500) not null,
    priority integer not null default 100,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table device (
    id uuid primary key,
    device_name varchar(200) not null,
    hostname varchar(255) not null,
    agent_version varchar(100),
    operating_system varchar(200),
    operating_system_version varchar(200),
    architecture varchar(100),
    local_ip_addresses jsonb not null default '[]',
    source_ip varchar(45),
    status varchar(50) not null,
    service_state varchar(50),
    session_state varchar(50),
    current_username varchar(255),
    idle_seconds bigint,
    registered_at timestamptz not null,
    last_registration_at timestamptz not null,
    last_heartbeat_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index device_hostname_idx on device(hostname);
create index device_status_idx on device(status);

create table device_event (
    id uuid primary key,
    device_id uuid not null references device(id),
    event_type varchar(100) not null,
    occurred_at timestamptz not null,
    received_at timestamptz not null,
    severity varchar(30) not null,
    source varchar(50) not null,
    session_id uuid,
    username varchar(255),
    source_ip varchar(45),
    data jsonb not null default '{}'
);

create index device_event_device_time_idx
    on device_event(device_id, occurred_at desc);

create index device_event_type_time_idx
    on device_event(event_type, occurred_at desc);

create index device_event_occurred_at_idx
    on device_event(occurred_at desc);

create table device_heartbeat (
    id uuid primary key,
    device_id uuid not null references device(id),
    occurred_at timestamptz not null,
    received_at timestamptz not null,
    uptime_seconds bigint,
    service_state varchar(50),
    session_state varchar(50),
    current_username varchar(255),
    idle_seconds bigint,
    source_ip varchar(45)
);

create index device_heartbeat_device_time_idx
    on device_heartbeat(device_id, occurred_at desc);

-- Bounds unbounded growth ahead of a scheduled retention job (see
-- HeartbeatProperties.historyRetentionDays); an index-only scan on this
-- makes the periodic delete cheap.
create index device_heartbeat_received_at_idx
    on device_heartbeat(received_at);
