alter table bookings alter column room_id drop not null;
alter table bookings add column auto_select boolean not null default false;
alter table bookings add column candidate_index integer not null default 0 check (candidate_index >= 0);
alter table bookings add column candidate_release_pending boolean not null default false;
alter table bookings add constraint ck_manual_room check (auto_select or room_id is not null);
alter table bookings add constraint ck_auto_hotel check (not auto_select or hotel_id is not null);
alter table bookings add constraint ck_confirmed_room check (status <> 'CONFIRMED' or room_id is not null);
create table booking_candidates (
    booking_id uuid not null references bookings(id) on delete cascade,
    candidate_order integer not null check (candidate_order >= 0),
    room_id uuid not null,
    primary key (booking_id, candidate_order),
    unique (booking_id, room_id)
);
