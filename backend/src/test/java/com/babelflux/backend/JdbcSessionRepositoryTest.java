package com.babelflux.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.infrastructure.JdbcSessionRepository;
import com.babelflux.backend.service.SessionReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcSessionRepositoryTest {
    @Test
    void roundTripsSessionSegmentsAndReportSnapshot() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:jdbc-repository;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table babelflux_sessions ("
                + "session_id varchar(64) primary key, created_at_epoch bigint not null, ended_at_epoch bigint, "
                + "status varchar(32) not null, session_name varchar(255) not null, source_language varchar(32) not null, "
                + "target_language varchar(32) not null, domain varchar(128) not null, model_profile varchar(128) not null, "
                + "product_mode varchar(32) not null, input_mode varchar(64) not null, source_label varchar(512) not null, "
                + "source_url varchar(2048), source_permission varchar(32) not null, tts_enabled boolean not null, "
                + "glossary_json text not null, "
                + "segments_json text not null, report_json text)");
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        JdbcSessionRepository repository = new JdbcSessionRepository(jdbc, mapper);
        SessionReportService reports = new SessionReportService();
        Session session = Session.create("jdbc-session", "JDBC test", "en", "zh", "通用",
                "智能默认", "quick", "demo", "demo", "https://example.test/audio", "granted", true,
                java.util.List.of(new Session.GlossaryTerm("API", "接口", 10, "preferred")));
        session.addSegment(new Session.Segment("seg-1", "hello", "你好", 1000, 2000, "final"));

        repository.save(session);
        assertEquals("created", repository.findById(session.getId()).orElseThrow().getStatus());
        assertEquals("created", repository.findByIdForUpdate(session.getId()).orElseThrow().getStatus());

        session.end();
        session.attachReport(reports.generate(session));
        repository.save(session);
        Session restored = repository.findById(session.getId()).orElseThrow();
        assertEquals("ended", restored.getStatus());
        assertEquals("你好", restored.getSegments().getFirst().translationText());
        assertEquals("https://example.test/audio", restored.getSourceUrl());
        assertEquals("granted", restored.getSourcePermission());
        assertTrue(restored.isTtsEnabled());
        assertEquals("接口", restored.getGlossary().getFirst().targetTerm());
        assertEquals(session.getReport().reportId(), restored.getReport().reportId());
        assertEquals(1, restored.getReport().metrics().segments());

        assertTrue(repository.deleteById(session.getId()));
        assertTrue(repository.findById(session.getId()).isEmpty());
    }

    @Test
    void persistsRunningStartupOverridesBeforeRealtimeWorkBegins() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:jdbc-startup;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table babelflux_sessions ("
                + "session_id varchar(64) primary key, created_at_epoch bigint not null, ended_at_epoch bigint, "
                + "status varchar(32) not null, session_name varchar(255) not null, source_language varchar(32) not null, "
                + "target_language varchar(32) not null, domain varchar(128) not null, model_profile varchar(128) not null, "
                + "product_mode varchar(32) not null, input_mode varchar(64) not null, source_label varchar(512) not null, "
                + "source_url varchar(2048), source_permission varchar(32) not null, tts_enabled boolean not null, "
                + "glossary_json text not null, segments_json text not null, report_json text)");
        JdbcSessionRepository repository = new JdbcSessionRepository(jdbc,
                JsonMapper.builder().addModule(new JavaTimeModule()).build());
        Session session = Session.create("startup-snapshot", "startup", "en", "zh", "通用", "智能默认",
                "quick", "demo", "demo", null, "idle", false, java.util.List.of());
        repository.save(session);

        session.applyOverrides("ja", "en", "running-state", "demo", null, "balanced");
        session.start();
        repository.save(session);

        Session restored = repository.findById("startup-snapshot").orElseThrow();
        assertEquals("running", restored.getStatus());
        assertEquals("ja", restored.getSourceLanguage());
        assertEquals("en", restored.getTargetLanguage());
        assertEquals("running-state", restored.getDomain());
        assertEquals("balanced", restored.getModelProfile());
    }
}
