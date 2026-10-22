-- A disabled row is kept but contributes no allowed window, so it can be switched off without losing it.
alter table calendar_rule add column enabled boolean not null default true;
