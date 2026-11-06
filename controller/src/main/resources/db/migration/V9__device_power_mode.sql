-- Per-device power override. power_mode ON follows the schedule (the default); OFF is off no matter
-- what, starting at off_from (which may be a little in the future, as a grace period). hold_until
-- ignores the schedule until then; hold_then_off keeps the device off from hold_until onwards.
alter table device add column power_mode varchar(10) not null default 'ON';
alter table device add column off_from timestamptz;
alter table device add column hold_until timestamptz;
alter table device add column hold_then_off boolean not null default false;
