package com.babelflux.backend.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcReportIndexJobStoreTest {
    @Test
    void enqueueIsIdempotentAndFailureStateIsInspectable() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:report-index-jobs;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table babelflux_report_index_jobs ("
                + "report_id varchar(128) primary key, payload text not null, status varchar(16) not null, "
                + "attempts int not null, next_attempt_at timestamp not null, last_error varchar(1000), "
                + "updated_at timestamp not null)");
        ObjectMapper mapper = JsonMapper.builder().build();
        JdbcReportIndexJobStore jobs = new JdbcReportIndexJobStore(jdbc, mapper);

        jobs.enqueue("report-1", Map.of("summary", "first"));
        jobs.enqueue("report-1", Map.of("summary", "second"));
        jobs.markFailed("report-1", Instant.now().minusSeconds(1), "ES unavailable");

        assertEquals(1, jdbc.queryForObject("select count(*) from babelflux_report_index_jobs", Integer.class));
        var status = jobs.status("report-1").orElseThrow();
        assertEquals("pending", status.status());
        assertEquals(1, status.attempts());
        assertEquals("ES unavailable", status.lastError());
        assertTrue(jobs.pending(10).getFirst().payload().contains("second"));
    }
}
