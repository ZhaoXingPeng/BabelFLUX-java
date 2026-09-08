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
    source_url varchar(2048),
    source_permission varchar(32) not null,
    tts_enabled boolean not null,
    glossary_json text not null,
    segments_json text not null,
    report_json text
);

create table if not exists babelflux_session_event_outbox (
    event_id varchar(64) primary key,
    event_type varchar(128) not null,
    schema_version integer not null,
    session_id varchar(64) not null,
    occurred_at timestamp not null,
    payload text not null,
    status varchar(16) not null,
    attempts integer not null default 0,
    next_attempt_at timestamp not null,
    created_at timestamp not null default current_timestamp,
    published_at timestamp null,
    lease_owner varchar(128),
    lease_until timestamp null
);

create table if not exists babelflux_session_event_receipts (
    event_id varchar(64) primary key,
    event_type varchar(128) not null,
    session_id varchar(64) not null,
    received_at timestamp not null default current_timestamp
);

create table if not exists babelflux_report_index_jobs (
    report_id varchar(128) primary key,
    payload text not null,
    status varchar(16) not null,
    attempts integer not null default 0,
    next_attempt_at timestamp not null,
    last_error varchar(1000),
    updated_at timestamp not null default current_timestamp,
    lease_owner varchar(128),
    lease_until timestamp null
);
