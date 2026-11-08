alter table bookings add column idempotency_key varchar(128);
alter table bookings add column request_fingerprint varchar(256);
alter table bookings add constraint uq_booking_user_key unique(user_id, idempotency_key);
alter table bookings add constraint ck_booking_request_identity check (
    (idempotency_key is null and request_fingerprint is null) or
    (idempotency_key is not null and request_fingerprint is not null)
);
