package com.babelflux.backend.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelflux.backend.infrastructure.mybatis.ReportIndexJobMapper;
import com.babelflux.backend.support.MyBatisMapperTestSupport;
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
                + "updated_at timestamp not null, lease_owner varchar(128), lease_until timestamp)");
        ObjectMapper mapper = JsonMapper.builder().build();
        JdbcReportIndexJobStore jobs = new JdbcReportIndexJobStore(
                MyBatisMapperTestSupport.mapper(jdbc.getDataSource(), ReportIndexJobMapper.class), mapper);

        jobs.enqueue("report-1", Map.of("summary", "first"));
        jobs.enqueue("report-1", Map.of("summary", "second"));
        String owner = "test-owner";
        assertTrue(jobs.tryClaim("report-1", owner, Instant.now().plusSeconds(30)));
        jobs.markFailed("report-1", owner, Instant.now().minusSeconds(1), "ES unavailable");

        assertEquals(1, jdbc.queryForObject("select count(*) from babelflux_report_index_jobs", Integer.class));
        var status = jobs.status("report-1").orElseThrow();
        assertEquals("pending", status.status());
        assertEquals(1, status.attempts());
        assertEquals("ES unavailable", status.lastError());
        assertTrue(jobs.pending(10).getFirst().payload().contains("second"));
    }

    @Test
    void allowsExpiredLeaseRecoveryButRejectsStaleOwnerUpdates() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:report-index-lease;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table babelflux_report_index_jobs ("
                + "report_id varchar(128) primary key, payload text not null, status varchar(16) not null, "
                + "attempts int not null, next_attempt_at timestamp not null, last_error varchar(1000), "
                + "updated_at timestamp not null, lease_owner varchar(128), lease_until timestamp)");
        JdbcReportIndexJobStore jobs = new JdbcReportIndexJobStore(
                MyBatisMapperTestSupport.mapper(jdbc.getDataSource(), ReportIndexJobMapper.class),
                JsonMapper.builder().build());
        jobs.enqueue("report-1", Map.of("summary", "lease"));

        assertTrue(jobs.tryClaim("report-1", "owner-a", Instant.now().plusSeconds(30)));
        assertFalse(jobs.tryClaim("report-1", "owner-b", Instant.now().plusSeconds(30)));
        jdbc.update("update babelflux_report_index_jobs set lease_until=? where report_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), "report-1");

        assertTrue(jobs.tryClaim("report-1", "owner-b", Instant.now().plusSeconds(30)));
        jobs.markIndexed("report-1", "owner-a");
        assertEquals("processing", jobs.status("report-1").orElseThrow().status());
        jobs.markIndexed("report-1", "owner-b");
        assertEquals("indexed", jobs.status("report-1").orElseThrow().status());
    }
}
