alter table bookings add column claim_token uuid;
alter table bookings add column lease_until timestamp(6) with time zone;
alter table bookings add column next_action_at timestamp(6) with time zone;
update bookings set next_action_at = created_at;
alter table bookings alter column next_action_at set not null;
alter table bookings add column recovery_failures integer not null default 0 check (recovery_failures >= 0);
create index ix_booking_recovery on bookings(status, next_action_at, lease_until);
