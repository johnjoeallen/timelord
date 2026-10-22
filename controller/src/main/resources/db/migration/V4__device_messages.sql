-- Free-text messages an admin sends to a device's logged-on user. seq gives
-- each device's agent a monotonic cursor to poll with (delivered_at is for the
-- admin UI only; the agent tracks what it has seen via seq).
create table device_message (
    id uuid primary key,
    seq bigint generated always as identity,
    device_id uuid not null references device(id) on delete cascade,
    title varchar(100),
    body varchar(500) not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    delivered_at timestamptz
);

create index device_message_device_seq_idx on device_message(device_id, seq);
