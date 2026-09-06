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
                + "segments_json text not null, report_json text)");
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        JdbcSessionRepository repository = new JdbcSessionRepository(jdbc, mapper);
        SessionReportService reports = new SessionReportService();
        Session session = new Session("jdbc-session", "JDBC test", "en", "zh", "通用",
                "智能默认", "quick", "demo", "demo");
        session.addSegment(new Session.Segment("seg-1", "hello", "你好", 1000, 2000, "final"));

        repository.save(session);
        assertEquals("created", repository.findById(session.getId()).orElseThrow().getStatus());

        session.end();
        session.attachReport(reports.generate(session));
        repository.save(session);
        Session restored = repository.findById(session.getId()).orElseThrow();
        assertEquals("ended", restored.getStatus());
        assertEquals("你好", restored.getSegments().getFirst().translationText());
        assertEquals(session.getReport().reportId(), restored.getReport().reportId());
        assertEquals(1, restored.getReport().metrics().segments());

        assertTrue(repository.deleteById(session.getId()));
        assertTrue(repository.findById(session.getId()).isEmpty());
    }
}
