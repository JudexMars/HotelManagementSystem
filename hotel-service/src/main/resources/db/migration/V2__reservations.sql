CREATE TABLE reservations (
    attempt_id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    room_id UUID NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_reservations_room FOREIGN KEY (room_id) REFERENCES rooms(id),
    CONSTRAINT ck_reservations_dates CHECK (end_date > start_date),
    CONSTRAINT ck_reservations_status CHECK (status IN ('HELD','RESERVED','EXPIRED','RELEASED')),
    CONSTRAINT ck_reservations_held_expiry CHECK (status <> 'HELD' OR expires_at IS NOT NULL)
);
CREATE INDEX ix_reservations_calendar ON reservations(room_id, status, start_date, end_date);
CREATE INDEX ix_reservations_expiry ON reservations(status, expires_at);
