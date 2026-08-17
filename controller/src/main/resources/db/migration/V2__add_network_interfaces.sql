-- Full per-adapter list (name + MAC), independent of local_ip_addresses
-- which stays the filtered, routable-only summary used elsewhere.
alter table device add column network_interfaces jsonb not null default '[]';
