CREATE TABLE hotels (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    address VARCHAR(500) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_hotels_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_hotels_address CHECK (CHAR_LENGTH(TRIM(address)) > 0)
);

CREATE TABLE rooms (
    id UUID PRIMARY KEY,
    hotel_id UUID NOT NULL,
    number VARCHAR(50) NOT NULL,
    available BOOLEAN NOT NULL DEFAULT TRUE,
    times_booked BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_rooms_hotel FOREIGN KEY (hotel_id) REFERENCES hotels(id),
    CONSTRAINT uk_rooms_hotel_number UNIQUE (hotel_id, number),
    CONSTRAINT ck_rooms_number CHECK (CHAR_LENGTH(TRIM(number)) > 0),
    CONSTRAINT ck_rooms_times_booked CHECK (times_booked >= 0)
);

CREATE INDEX ix_hotels_catalog ON hotels(deleted, name, id);
CREATE INDEX ix_rooms_catalog ON rooms(hotel_id, deleted, available);
