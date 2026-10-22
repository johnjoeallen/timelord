-- A disabled override is kept but adds no allowed window, so it can be switched off and back on.
alter table calendar_override add column enabled boolean not null default true;
