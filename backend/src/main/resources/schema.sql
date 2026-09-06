create table if not exists babelflux_session_audit (
    session_id varchar(64) not null,
    status varchar(32) not null,
    created_at timestamp default current_timestamp
);
