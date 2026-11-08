create table bookings (
    id uuid primary key,
    user_id uuid not null references users(id),
    room_id uuid not null,
    hotel_id uuid,
    start_date date not null,
    end_date date not null,
    created_at timestamp(6) with time zone not null,
    status varchar(16) not null check (status in ('PENDING', 'CONFIRMED', 'CANCELLED')),
    step varchar(32) not null check (step in ('RESOLVE_ROOM', 'HOLD', 'COMMIT', 'DONE')),
    attempt_id uuid not null unique,
    compensation_status varchar(16) not null check (compensation_status in ('NONE', 'PENDING', 'DONE')),
    version bigint not null default 0,
    constraint ck_booking_dates check (end_date > start_date),
    constraint ck_confirmed_hotel check (status <> 'CONFIRMED' or hotel_id is not null)
);
create index ix_booking_owner_created on bookings(user_id, created_at, id);
create index ix_booking_pending_step on bookings(status, step);
