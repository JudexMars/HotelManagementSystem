alter table bookings add column deadline timestamp(6) with time zone;
update bookings set deadline = dateadd('SECOND', 20, created_at);
alter table bookings alter column deadline set not null;
alter table bookings add column failure_code varchar(64);
