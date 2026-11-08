create table users (
    id uuid primary key,
    username varchar(100) not null,
    password varchar(100) not null,
    role varchar(16) not null check (role in ('USER', 'ADMIN')),
    deleted boolean not null default false,
    version bigint not null default 0,
    constraint uk_users_username unique (username),
    constraint ck_users_username_length check (char_length(username) between 1 and 100)
);
