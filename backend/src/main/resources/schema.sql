create table if not exists babelflux_session_audit (
    session_id varchar(64) not null,
    status varchar(32) not null,
    created_at timestamp default current_timestamp
);

create table if not exists babelflux_sessions (
    session_id varchar(64) primary key,
    created_at_epoch bigint not null,
    ended_at_epoch bigint,
    status varchar(32) not null,
    session_name varchar(255) not null,
    source_language varchar(32) not null,
    target_language varchar(32) not null,
    domain varchar(128) not null,
    model_profile varchar(128) not null,
    product_mode varchar(32) not null,
    input_mode varchar(64) not null,
    source_label varchar(512) not null,
    segments_json text not null,
    report_json text
);
