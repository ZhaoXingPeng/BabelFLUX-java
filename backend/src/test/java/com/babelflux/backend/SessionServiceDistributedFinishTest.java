package com.babelflux.backend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.babelflux.backend.config.BabelFluxProperties;
import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import com.babelflux.backend.infrastructure.JdbcSessionRepository;
import com.babelflux.backend.messaging.JdbcSessionEventOutbox;
import com.babelflux.backend.messaging.SessionEventFactory;
import com.babelflux.backend.search.ReportIndexingPort;
import com.babelflux.backend.service.SessionReportService;
import com.babelflux.backend.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class SessionServiceDistributedFinishTest {
    @Test
    void concurrentFinishAcrossTransactionsGeneratesOneReport() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:distributed-finish;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table babelflux_sessions ("
                + "session_id varchar(64) primary key, created_at_epoch bigint not null, ended_at_epoch bigint, "
                + "status varchar(32) not null, session_name varchar(255) not null, source_language varchar(32) not null, "
                + "target_language varchar(32) not null, domain varchar(128) not null, model_profile varchar(128) not null, "
                + "product_mode varchar(32) not null, input_mode varchar(64) not null, source_label varchar(512) not null, "
                + "source_url varchar(2048), source_permission varchar(32) not null, tts_enabled boolean not null, "
                + "glossary_json text not null, segments_json text not null, report_json text)");
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        JdbcSessionRepository repository = new JdbcSessionRepository(jdbc, mapper);
        Session session = Session.create("distributed-finish", "race", "en", "zh", "通用", "默认",
                "quick", "demo", "demo", null, "idle", false, List.of());
        repository.save(session);

        SessionReportService reports = mock(SessionReportService.class);
        SessionReport report = report(session.getId());
        CountDownLatch generationEntered = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        when(reports.generate(any(Session.class))).thenAnswer(invocation -> {
            generationEntered.countDown();
            assertTrue(releaseGeneration.await(2, TimeUnit.SECONDS));
            return report;
        });
        SessionService firstService = service(repository, reports);
        SessionService secondService = service(repository, reports);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SessionReport> first = executor.submit(() -> transaction.execute(status -> firstService.finish(session.getId())));
            assertTrue(generationEntered.await(1, TimeUnit.SECONDS));
            Future<SessionReport> second = executor.submit(() -> transaction.execute(status -> secondService.finish(session.getId())));
            Thread.sleep(100);
            assertFalse(second.isDone(), "the second transaction must wait for the session row lock");
            releaseGeneration.countDown();
            assertEquals(report.reportId(), first.get(2, TimeUnit.SECONDS).reportId());
            assertEquals(report.reportId(), second.get(2, TimeUnit.SECONDS).reportId());
        }
        verify(reports, times(1)).generate(any(Session.class));
        Session restored = repository.findById(session.getId()).orElseThrow();
        assertTrue(restored.getReport() != null);
        assertTrue("ended".equals(restored.getStatus()));
    }

    private static SessionService service(JdbcSessionRepository repository, SessionReportService reports) {
        return new SessionService(repository, reports, new BabelFluxProperties(),
                mock(JdbcSessionEventOutbox.class), mock(SessionEventFactory.class), mock(ReportIndexingPort.class));
    }

    private static SessionReport report(String sessionId) {
        return new SessionReport(sessionId + "-report", sessionId, "race", "quick", "demo", "demo",
                "通用", "默认", "en", "zh", 0, "00:00", Instant.now(), "summary", "notes", List.of(),
                new SessionReport.Metrics(0, 0, 0, "00:00"), List.of(), List.of(), List.of(), "none",
                "skipped", "", 0);
    }
}
