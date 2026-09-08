package com.babelflux.backend.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.babelflux.backend.config.BabelFluxProperties;
import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import com.babelflux.backend.infrastructure.InMemorySessionRepository;
import com.babelflux.backend.messaging.JdbcSessionEventOutbox;
import com.babelflux.backend.messaging.SessionEventFactory;
import com.babelflux.backend.search.ReportIndexingPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SessionServiceTest {
    @Test
    void concurrentFinishCallsGenerateAndPublishOneReport() throws Exception {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        SessionReportService reports = mock(SessionReportService.class);
        ReportIndexingPort indexing = mock(ReportIndexingPort.class);
        SessionEventFactory events = mock(SessionEventFactory.class);
        Session session = Session.create("finish-race", "race", "en", "zh", "通用", "默认",
                "quick", "demo", "demo", null, "idle", false, List.of());
        repository.save(session);
        SessionReport report = report("finish-race");
        CountDownLatch generationEntered = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        when(reports.generate(any(Session.class))).thenAnswer(invocation -> {
            generationEntered.countDown();
            assertTrue(releaseGeneration.await(2, TimeUnit.SECONDS));
            return report;
        });
        SessionService service = new SessionService(repository, reports, new BabelFluxProperties(),
                mock(JdbcSessionEventOutbox.class), events, indexing);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SessionReport> first = executor.submit(() -> service.finish(session.getId()));
            assertTrue(generationEntered.await(1, TimeUnit.SECONDS));
            CountDownLatch secondEntered = new CountDownLatch(1);
            Future<SessionReport> second = executor.submit(() -> {
                secondEntered.countDown();
                return service.finish(session.getId());
            });
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertFalse(second.isDone(), "the second caller must wait for the in-flight report");
            verify(reports, times(1)).generate(any(Session.class));
            releaseGeneration.countDown();

            assertSame(report, first.get(2, TimeUnit.SECONDS));
            assertSame(report, second.get(2, TimeUnit.SECONDS));
        }
        verify(reports, times(1)).generate(any(Session.class));
        verify(indexing, times(1)).enqueue(report);
        verify(events, times(1)).finished(any(Session.class), any(SessionReport.class));
        verify(events, times(1)).reportGenerated(any(Session.class), any(SessionReport.class));
    }

    private static SessionReport report(String sessionId) {
        return new SessionReport(sessionId + "-report", sessionId, "race", "quick", "demo", "demo",
                "通用", "默认", "en", "zh", 0, "00:00", Instant.now(), "summary", "notes", List.of(),
                new SessionReport.Metrics(0, 0, 0, "00:00"), List.of(), List.of(), List.of(), "none",
                "skipped", "", 0);
    }
}
