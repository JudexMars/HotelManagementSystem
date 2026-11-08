alter table bookings add column trace_id varchar(64) not null default 'legacy-session';
